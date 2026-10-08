// JClaw Plugin-Sidecar für die Plugin-Laufzeit (P4-01, siehe docs/bridge-protocol.md §8).
//
// Basierend auf protocol-sidecar.js: gleiche JSON-RPC-2.0-Framing-Regeln (NDJSON über
// stdio, sidecar.ready-Handshake, sidecar.ping/info/listTools/tool.call). Zusätzlich
// registrieren Plugins ihre Tools/Commands/Hooks zur Laufzeit über die OpenClaw-Entry-
// Semantik (definePluginEntry / defineChannelPluginEntry).
//
// Entry-Vertrag: zwei Lademodi. (1) Legacy-Source-Modus ({id, source}): der Source
// wird als CommonJS-Entry in einer vm-Sandbox evaluiert (ohne require). (2) Bundle-
// Modus ({id, entryPath, baseDir}, P4-01 npm/TypeScript-Bundles): das Entry wird als
// reale Datei innerhalb des Plugin-Ordners geladen und kompiliert — mit hermetischem
// require-Scope (relative Module, <baseDir>/node_modules, Node-Builtins; alles, was
// den Plugin-Ordner verlässt, ist abgewiesen) und TypeScript-Type-Stripping
// (.ts/.mts/.cts, erasable Syntax, Node >= 22.6). Beispiel (CommonJS):
//
//   module.exports = definePluginEntry({
//     id: 'my-plugin',
//     name: 'My Plugin',
//     register(api) {
//       api.registerTool({
//         name: 'greet',
//         description: 'Begrüßt jemanden.',
//         parameters: {
//           type: 'object',
//           properties: { name: { type: 'string' } },
//           required: ['name']
//         },
//         execute(args) { return { greeting: 'Hallo ' + args.name }; }
//       });
//       api.on('before_tool_call', (ctx) => {
//         if (ctx.arguments && ctx.arguments.name === 'block') {
//           throw new Error('Name ist gesperrt.');
//         }
//       }, { matcher: 'greet', priority: 10 });
//     }
//   });
//
// Channel-Plugins (defineChannelPluginEntry) registrieren ihre Channels über
// api.registerChannel({ name, description, receive }); eingehende Nachrichten stellt der
// Java-Kern über die JSON-RPC-Methode channel.deliver an die receive-Handler zu (Empfang
// über dieselbe Bridge; Fehler: ERROR_CHANNEL_NOT_FOUND / ERROR_CHANNEL_EXECUTION).
//
// Fehlercodes: siehe NodeSidecarBridge (ERROR_*).

'use strict';

const readline = require('readline');
const vm = require('vm');
const fs = require('fs');
const path = require('path');
const Module = require('module');

const ERROR_METHOD_NOT_FOUND = -32601;
const ERROR_INVALID_PARAMS = -32602;
const ERROR_TOOL_NOT_FOUND = -32001;
const ERROR_TOOL_EXECUTION = -32002;
const ERROR_INTERNAL = -32003;
const ERROR_HOOK_BLOCKED = -32005;
const ERROR_PLUGIN_INVALID = -32006;
const ERROR_CHANNEL_NOT_FOUND = -32007;
const ERROR_CHANNEL_EXECUTION = -32008;

const SIDECAR_NAME = 'jclaw-plugin-sidecar';
const SIDECAR_VERSION = '1.0.0';

// Referenz-Tools (Identisch zu protocol-sidecar.js). `run` wirft bei ungültigen
// Argumenten -> ERROR_TOOL_EXECUTION.
const staticTools = {
  add: {
    description: 'Berechnet die Summe zweier Zahlen a + b.',
    parameters: {
      type: 'object',
      properties: {
        a: { type: 'number', description: 'Erster Summand.' },
        b: { type: 'number', description: 'Zweiter Summand.' }
      },
      required: ['a', 'b']
    },
    run(args) {
      if (typeof args.a !== 'number' || typeof args.b !== 'number') {
        throw new Error('a und b müssen Zahlen sein.');
      }
      return { result: args.a + args.b };
    }
  },
  echo: {
    description: 'Gibt den übergebenen Text unverändert zurück.',
    parameters: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'Der zurückzugebende Text.' }
      },
      required: ['text']
    },
    run(args) {
      return { text: String(args.text) };
    }
  },
  sleep: {
    description: 'Wartet ms Millisekunden und bestätigt das Warten.',
    parameters: {
      type: 'object',
      properties: {
        ms: { type: 'number', description: 'Wartezeit in Millisekunden.' }
      },
      required: ['ms']
    },
    run(args) {
      const ms = Number(args.ms);
      if (!Number.isFinite(ms) || ms < 0) {
        throw new Error('ms muss eine nicht-negative Zahl sein.');
      }
      const sab = new SharedArrayBuffer(4);
      Atomics.wait(new Int32Array(sab), 0, 0, ms);
      return { sleptMs: ms };
    }
  }
};

// Laufzeit-Registries. Statische Tools sind Default; Plugin-Tools können einen Namen
// überschreiben, beim unload wird der statische Default wiederhergestellt (siehe auch
// `restoreStaticTool`).
const tools = new Map();    // name -> { pluginId, description, parameters, outputSchema, run }
const commands = new Map(); // name -> { pluginId, description, run }
const channels = new Map(); // name -> { pluginId, name, description, receive }
const hooks = [];           // { pluginId, event, handler, matcher, priority }
const plugins = new Map();  // id -> { id, name, tools: [], commands: [], channels: [], hooks: [] }

for (const name of Object.keys(staticTools)) {
  tools.set(name, { pluginId: null, name, ...staticTools[name] });
}

// Voll-Hook-Katalog (P4-01 Folgearbeit): alle modernen OpenClaw-Hook-Stages (2026.8.x,
// ohne die Legacy-Stage 'before_agent_start'). JClaw emittiert davon auf der Java-Seite
// einen Teil der Stages (Verdrahtung siehe docs/parity-roadmap.md); der Katalog ist die
// Registrierungs-API, nicht das Versprechen, dass jede Runtime jeden Hook emittiert.
const HOOK_EVENTS = [
  'before_model_resolve', 'agent_turn_prepare', 'before_prompt_build',
  'before_agent_run', 'before_agent_reply', 'before_agent_finalize', 'agent_end',
  'llm_input', 'llm_output', 'model_call_started', 'model_call_ended',
  'before_tool_call', 'after_tool_call', 'tool_result_persist',
  'message_received', 'message_sending', 'message_sent', 'reply_payload_sending',
  'before_message_write', 'before_dispatch', 'reply_dispatch',
  'session_start', 'session_end',
  'before_compaction', 'after_compaction', 'before_reset',
  'gateway_start', 'gateway_stop', 'cron_reconciled', 'cron_changed',
  'before_install', 'resolve_exec_env',
  'skill_proposal_evaluate', 'skill_changed'
];

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function sendResult(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

function matcherMatches(matcher, name) {
  if (matcher === undefined || matcher === null) {
    return true;
  }
  if (typeof matcher === 'string') {
    return matcher === name;
  }
  if (matcher instanceof RegExp || (matcher && typeof matcher.test === 'function')) {
    matcher.lastIndex = 0;
    return matcher.test(name);
  }
  if (typeof matcher === 'function') {
    return matcher(name);
  }
  return false;
}

function matchingHooks(event, name) {
  return hooks
    .filter((hook) => hook.event === event && matcherMatches(hook.matcher, name))
    .sort((a, b) => (b.priority || 0) - (a.priority || 0));
}

function restoreStaticTool(name) {
  if (Object.prototype.hasOwnProperty.call(staticTools, name)) {
    tools.set(name, { pluginId: null, name, ...staticTools[name] });
  }
}

function unloadById(id) {
  const plugin = plugins.get(id);
  if (!plugin) {
    return false;
  }
  for (const name of plugin.tools) {
    const current = tools.get(name);
    if (current && current.pluginId === id) {
      tools.delete(name);
      restoreStaticTool(name);
    }
  }
  for (const name of plugin.commands) {
    const current = commands.get(name);
    if (current && current.pluginId === id) {
      commands.delete(name);
    }
  }
  for (const name of plugin.channels) {
    const current = channels.get(name);
    if (current && current.pluginId === id) {
      channels.delete(name);
    }
  }
  for (const hook of plugin.hooks) {
    const index = hooks.indexOf(hook);
    if (index >= 0) {
      hooks.splice(index, 1);
    }
  }
  plugins.delete(id);
  return true;
}

function createPluginApi(id) {
  return {
    registerTool({ name, description, parameters, outputSchema, execute }) {
      if (typeof name !== 'string' || name.length === 0 || typeof execute !== 'function') {
        throw new Error('registerTool benötigt name (String) und execute (Funktion).');
      }
      tools.set(name, { pluginId: id, name, description, parameters, outputSchema, run: execute });
      const plugin = plugins.get(id);
      if (!plugin.tools.includes(name)) {
        plugin.tools.push(name);
      }
    },
    registerCommand({ name, description, execute }) {
      if (typeof name !== 'string' || name.length === 0 || typeof execute !== 'function') {
        throw new Error('registerCommand benötigt name (String) und execute (Funktion).');
      }
      commands.set(name, { pluginId: id, name, description, run: execute });
      const plugin = plugins.get(id);
      if (!plugin.commands.includes(name)) {
        plugin.commands.push(name);
      }
    },
    registerChannel({ name, description, receive }) {
      if (typeof name !== 'string' || name.length === 0 || typeof receive !== 'function') {
        throw new Error('registerChannel benötigt name (String) und receive (Funktion).');
      }
      channels.set(name, { pluginId: id, name, description, receive });
      const plugin = plugins.get(id);
      if (!plugin.channels.includes(name)) {
        plugin.channels.push(name);
      }
    },
    on(event, handler, options) {
      if (typeof handler !== 'function') {
        throw new Error('on() benötigt einen Handler (Funktion).');
      }
      if (typeof event !== 'string' || !HOOK_EVENTS.includes(event)) {
        throw new Error('Unbekannter Hook-Event: ' + event + ' (unterstützt: ' + HOOK_EVENTS.join(', ') + ').');
      }
      const priority = options && typeof options.priority === 'number' ? options.priority : 0;
      const matcher = options ? options.matcher : undefined;
      // Per-Hook-Timeout (OpenClaw plugins.entries.<id>.hooks.timeoutMs): wird callHook
      // mit einem Timeout ausgestattet, bricht er den Hook ab und behandelt das als Block.
      const timeoutMs = options && typeof options.timeoutMs === 'number' && options.timeoutMs > 0
          ? options.timeoutMs
          : undefined;
      const hook = { pluginId: id, event, handler, matcher, priority, timeoutMs };
      hooks.push(hook);
      plugins.get(id).hooks.push(hook);
    }
  };
}

function evaluateEntry(id, source) {
  const sandbox = {
    module: { exports: {} },
    exports: {},
    console,
    definePluginEntry: (entry) => entry,
    defineChannelPluginEntry: (entry) => entry
  };
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  const script = new vm.Script(source, { filename: id + '.js' });
  script.runInContext(sandbox);
  let entry = sandbox.module.exports;
  if (entry && typeof entry === 'object' && entry.default) {
    entry = entry.default; // z. B. aus transpiliertem ESM: exports.default = definePluginEntry(...)
  }
  return entry || null;
}

// ---- Bundle-Modus (npm/TypeScript, P4-01) ------------------------------------------
//
// Echte Plugin-Bundles werden als reale Datei (entryPath innerhalb von baseDir) geladen
// und als CommonJS kompiliert — mit gebündeltem, hermetischem `require`-Scope: relative
// Module, <baseDir>/node_modules (für npm-Abhängigkeiten) und Node-Builtins sind erlaubt;
// jede Auflösung, die den Plugin-Ordner verlässt (absolute Pfade, `..`-Aufstieg),
// scheitert mit ERROR_PLUGIN_INVALID. TypeScript-Entries (.ts/.mts/.cts) laufen über das
// native Type-Stripping des Node-CJS-Loaders (Node >= 22.6; nur erasable Syntax — kein
// export=, enum oder namespace). Der Modul-Cache ist pro plugin.load frisch, sodass ein
// Hot-Reload (erneutes plugin.load mit derselben id) die Registrierungen neu aufbaut.

const MODULE_EXTENSIONS = ['.js', '.cjs', '.mjs', '.ts', '.mts', '.cts', '.json'];
const TYPE_SCRIPT_EXTENSIONS = ['.ts', '.mts', '.cts'];
const PROLOGUE = 'const definePluginEntry=(entry)=>entry;const defineChannelPluginEntry=(entry)=>entry;';

function isTypeScriptFile(file) {
  return TYPE_SCRIPT_EXTENSIONS.some((ext) => file.endsWith(ext));
}

function isBuiltin(request) {
  return typeof Module.isBuiltin === 'function'
      ? Module.isBuiltin(request)
      : Module.builtinModules.includes(request);
}

function isInside(baseDir, target) {
  const base = path.resolve(baseDir);
  const resolved = path.resolve(target);
  return resolved === base || resolved.startsWith(base + path.sep);
}

function assertInside(baseDir, target, pluginId) {
  if (!isInside(baseDir, target)) {
    throw new Error(
        "Plugin '" + pluginId + "': '" + target + "' verlässt den Plugin-Ordner (hermetische Grenze).");
  }
  return path.resolve(target);
}

function statIs(target, kind) {
  try {
    const stat = fs.statSync(target);
    return kind === 'file' ? stat.isFile() : stat.isDirectory();
  } catch {
    return false;
  }
}

function resolveAsFile(baseDir, target, pluginId) {
  assertInside(baseDir, target, pluginId);
  if (statIs(target, 'file')) {
    return path.resolve(target);
  }
  for (const ext of MODULE_EXTENSIONS) {
    const candidate = target + ext;
    if (statIs(candidate, 'file')) {
      return path.resolve(candidate);
    }
  }
  return null;
}

function resolveIndexFile(baseDir, target, pluginId) {
  for (const ext of MODULE_EXTENSIONS) {
    const candidate = path.join(target, 'index' + ext);
    if (statIs(candidate, 'file')) {
      assertInside(baseDir, candidate, pluginId);
      return path.resolve(candidate);
    }
  }
  return null;
}

function resolveAsDirectory(baseDir, target, pluginId) {
  if (!statIs(target, 'dir')) {
    return null;
  }
  const packageJson = path.join(target, 'package.json');
  if (statIs(packageJson, 'file')) {
    try {
      const main = JSON.parse(fs.readFileSync(packageJson, 'utf8')).main;
      if (typeof main === 'string' && main.length > 0) {
        const mainTarget = path.resolve(target, main);
        const resolved = resolveAsFile(baseDir, mainTarget, pluginId)
            || resolveAsDirectory(baseDir, mainTarget, pluginId);
        if (resolved) {
          return resolved;
        }
      }
    } catch (ignored) {
      // Ungültiges package.json: wie ein Verzeichnis ohne main behandeln
    }
  }
  return resolveIndexFile(baseDir, target, pluginId);
}

function stripTypeScript(filename, source) {
  if (typeof Module.stripTypeScriptTypes !== 'function') {
    throw new Error("Plugin '" + filename + "': TypeScript-Support benötigt Node >= 22.6 (Type Stripping; "
        + 'aktuell: ' + process.version + ').');
  }
  try {
    return Module.stripTypeScriptTypes(source);
  } catch (err) {
    throw new Error("Plugin '" + filename + "': TypeScript nur mit erasable Syntax (kein export=, enum, "
        + 'namespace): ' + (err.message || String(err)));
  }
}

/**
 * Erzeugt den hermetischen Modul-Loader eines Plugin-Bundles.
 * Liefert { evaluateModule(file), resolveRequest(request) }; der Modul-Cache ist pro
 * Load frisch (Hot-Reload: jedes plugin.load wertet das Entry neu aus).
 */
function createModuleLoader(pluginId, baseDir) {
  const base = path.resolve(baseDir);
  const cache = new Map();

  function resolveRequest(request, fromFile) {
    if (isBuiltin(request)) {
      return request;
    }
    const fromDir = fromFile ? path.dirname(path.resolve(fromFile)) : base;
    if (request.startsWith('.') || path.isAbsolute(request)) {
      const target = path.isAbsolute(request) ? path.resolve(request) : path.resolve(fromDir, request);
      assertInside(base, target, pluginId);
      const resolved = resolveAsFile(base, target, pluginId) || resolveAsDirectory(base, target, pluginId);
      if (resolved) {
        return resolved;
      }
      throw new Error("Plugin '" + pluginId + "': Modul '" + request + "' wurde nicht gefunden "
          + '(nur innerhalb des Plugin-Ordners erlaubt).');
    }
    // npm-Specifier: nur node_modules-Verzeichnisse innerhalb des Plugin-Ordners erreichen
    let dir = fromDir;
    while (true) {
      const candidate = path.join(dir, 'node_modules', request);
      if (isInside(base, candidate)) {
        const resolved = resolveAsFile(base, candidate, pluginId)
            || resolveAsDirectory(base, candidate, pluginId);
        if (resolved) {
          return resolved;
        }
      }
      if (dir === base) {
        break;
      }
      const parent = path.dirname(dir);
      if (parent === dir || !isInside(base, parent)) {
        break;
      }
      dir = parent;
    }
    throw new Error("Plugin '" + pluginId + "': Modul '" + request + "' wurde nicht in node_modules "
        + 'des Plugin-Ordners gefunden.');
  }

  function compileModule(filename, source) {
    const m = new Module(filename, module);
    m.filename = filename;
    m.paths = Module._nodeModulePaths(base);
    m.require = scopedRequire;
    let code = source;
    if (isTypeScriptFile(filename)) {
      code = stripTypeScript(filename, source);
    }
    m._compile(PROLOGUE + '\n' + code, filename);
    return m;
  }

  function evaluateModule(filename) {
    const resolved = assertInside(base, filename, pluginId);
    if (cache.has(resolved)) {
      return cache.get(resolved);
    }
    let m;
    if (resolved.endsWith('.json')) {
      m = new Module(resolved, module);
      m.filename = resolved;
      m.paths = Module._nodeModulePaths(base);
      m.require = scopedRequire;
      m.exports = JSON.parse(fs.readFileSync(resolved, 'utf8'));
    } else {
      m = compileModule(resolved, fs.readFileSync(resolved, 'utf8'));
    }
    cache.set(resolved, m);
    return m;
  }

  function scopedRequire(request) {
    const fromFile = this && this.filename ? this.filename : base;
    const resolved = resolveRequest(request, fromFile);
    if (resolved === request) {
      // Node-Builtin (z. B. 'fs', 'path'): über den ursprünglichen Module.require laden
      return Module.prototype.require.call(this && this instanceof Module ? this : module, resolved);
    }
    const exports = evaluateModule(resolved).exports;
    return exports && typeof exports === 'object' && exports.__esModule && exports.default !== undefined
        ? exports.default
        : exports;
  }
  scopedRequire.resolve = (request) => {
    const resolved = resolveRequest(request, base);
    return resolved === request ? resolved : assertInside(base, resolved, pluginId);
  };
  scopedRequire.resolve.paths = () => [path.join(base, 'node_modules')];

  return { evaluateModule, resolveRequest };
}

function evaluateBundle(id, entryPath, baseDir) {
  const loader = createModuleLoader(id, baseDir);
  const entryModule = loader.evaluateModule(entryPath);
  let entry = entryModule.exports;
  if (entry && typeof entry === 'object' && entry.default) {
    entry = entry.default; // z. B. aus transpiliertem ESM: exports.default = definePluginEntry(...)
  }
  return entry || null;
}

function loadPlugin(req) {
  const id = req.params && req.params.id;
  const source = req.params && req.params.source;
  const entryPath = req.params && req.params.entryPath;
  const baseDir = req.params && req.params.baseDir;
  const bundleMode = typeof entryPath === 'string' && entryPath.length > 0
      && typeof baseDir === 'string' && baseDir.length > 0;
  if (typeof id !== 'string' || id.length === 0) {
    sendError(req.id, ERROR_PLUGIN_INVALID, 'plugin.load benötigt id (String).');
    return;
  }
  if (!bundleMode && (typeof source !== 'string' || source.length === 0)) {
    sendError(req.id, ERROR_PLUGIN_INVALID,
        'plugin.load benötigt source (String) oder entryPath+baseDir (Bundle-Modus).');
    return;
  }

  if (plugins.has(id)) {
    unloadById(id); // Erneut laden (Hot-Reload-Semantik): alte Registrierungen entfernen
  }

  let entry;
  try {
    entry = bundleMode ? evaluateBundle(id, entryPath, baseDir) : evaluateEntry(id, source);
  } catch (err) {
    sendError(req.id, ERROR_PLUGIN_INVALID, "Plugin-Source '" + id + "' ist ungültig: " + (err.message || String(err)));
    return;
  }

  if (!entry || typeof entry.register !== 'function') {
    sendError(req.id, ERROR_PLUGIN_INVALID,
        "Plugin-Source '" + id + "' registriert keine Entry (definePluginEntry/defineChannelPluginEntry fehlt).");
    return;
  }

  plugins.set(id, { id, name: entry.name || id, tools: [], commands: [], channels: [], hooks: [] });
  const api = createPluginApi(id);
  try {
    entry.register(api);
  } catch (err) {
    unloadById(id);
    sendError(req.id, ERROR_PLUGIN_INVALID, 'Fehler bei register() von Plugin \'' + id + '\': ' + (err.message || String(err)));
    return;
  }

  const plugin = plugins.get(id);
  const registeredTools = plugin.tools.map((name) => {
    const tool = tools.get(name);
    return { name, description: tool.description, parameters: tool.parameters };
  });
  const registeredChannels = plugin.channels.map((name) => {
    const channel = channels.get(name);
    return { name, description: channel.description };
  });
  sendResult(req.id, {
    id,
    name: plugin.name,
    tools: registeredTools,
    commands: plugin.commands.slice(),
    hooks: plugin.hooks.map((hook) => ({
      event: hook.event,
      priority: hook.priority,
      ...(hook.timeoutMs !== undefined ? { timeoutMs: hook.timeoutMs } : {})
    })),
    channels: registeredChannels
  });
}

function unloadPlugin(req) {
  const id = req.params && req.params.id;
  const removed = typeof id === 'string' ? unloadById(id) : false;
  sendResult(req.id, { id, removed });
}

function callTool(req) {
  const name = req.params && req.params.name;
  const args = (req.params && req.params.arguments) || {};
  const tool = tools.get(name);
  if (!tool) {
    sendError(req.id, ERROR_TOOL_NOT_FOUND, 'Unbekanntes Tool: ' + name);
    return;
  }

  for (const hook of matchingHooks('before_tool_call', name)) {
    try {
      hook.handler({ toolName: name, arguments: args });
    } catch (err) {
      sendError(req.id, ERROR_HOOK_BLOCKED,
          "before_tool_call-Hook hat den Aufruf '" + name + "' blockiert: " + (err.message || String(err)));
      return;
    }
  }

  let result;
  try {
    result = tool.run(args);
  } catch (err) {
    sendError(req.id, ERROR_TOOL_EXECUTION, err.message || String(err));
    return;
  }

  for (const hook of matchingHooks('after_tool_call', name)) {
    try {
      hook.handler({ toolName: name, result });
    } catch (err) {
      sendError(req.id, ERROR_HOOK_BLOCKED,
          "after_tool_call-Hook hat das Ergebnis von '" + name + "' blockiert: " + (err.message || String(err)));
      return;
    }
  }

  sendResult(req.id, result);
}

function deliverChannel(req) {
  const name = req.params && req.params.channel;
  const message = (req.params && req.params.message) || {};
  const channel = channels.get(name);
  if (!channel) {
    sendError(req.id, ERROR_CHANNEL_NOT_FOUND, 'Unbekannter Channel: ' + name);
    return;
  }

  let result;
  try {
    result = channel.receive(message);
  } catch (err) {
    sendError(req.id, ERROR_CHANNEL_EXECUTION,
        "receive()-Handler des Channels '" + name + "' warf: " + (err.message || String(err)));
    return;
  }

  sendResult(req.id, result !== undefined ? result : { delivered: true });
}

/**
 * Führt einen Hook-Handler aus — mit optionalem Per-Hook-Timeout. Ein Timeout
 * (abgelaufener Timer) wird als Werfen behandelt und damit als Block gewertet.
 */
async function runHook(hook, ctx) {
  const invoke = () => hook.handler(ctx);
  const timeoutMs = hook.timeoutMs;
  if (typeof timeoutMs !== 'number' || timeoutMs <= 0) {
    return invoke();
  }
  let timer;
  try {
    return await Promise.race([
      Promise.resolve().then(invoke),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('Timeout nach ' + timeoutMs + ' ms.')), timeoutMs);
      })
    ]);
  } finally {
    if (timer) {
      clearTimeout(timer);
    }
  }
}

/**
 * Generischer Hook-Dispatch (P4-01 Folgearbeit „Voll-Hook-Katalog").
 *
 * Löst alle für {@code event} (+ optionalen {@code matcher}-Namen) registrierten Hooks
 * in Prioritäts-Reihenfolge aus. Blocking: Ein Hook wirft ODER liefert
 * {@code {block: true}} / {@code {blocked: true}} — dann stoppt der Dispatch und das
 * Ergebnis trägt {@code blocked: true} plus Nachricht. Unbekannte Stages liefern
 * {@code count: 0, blocked: false} (Katalog = Registrierungs-API, keine Fehlerstufe).
 */
async function callHook(req) {
  const event = req.params && req.params.event;
  if (typeof event !== 'string' || event.length === 0) {
    sendError(req.id, ERROR_INVALID_PARAMS, 'plugin.callHook benötigt event (String).');
    return;
  }
  const name = (req.params && typeof req.params.name === 'string' && req.params.name.length > 0)
      ? req.params.name
      : undefined;
  const ctx = (req.params && req.params.ctx && typeof req.params.ctx === 'object') ? req.params.ctx : {};

  let count = 0;
  for (const hook of matchingHooks(event, name)) {
    count++;
    let outcome;
    try {
      outcome = await runHook(hook, ctx);
    } catch (err) {
      sendResult(req.id, {
        event, count, blocked: true,
        message: event + '-Hook hat blockiert: ' + (err.message || String(err))
      });
      return;
    }
    if (outcome && typeof outcome === 'object' && (outcome.block === true || outcome.blocked === true)) {
      sendResult(req.id, {
        event, count, blocked: true,
        message: (typeof outcome.message === 'string' && outcome.message.length > 0)
            ? outcome.message
            : event + '-Hook hat blockiert.'
      });
      return;
    }
  }
  sendResult(req.id, { event, count, blocked: false });
}

function handleRequest(req) {
  switch (req.method) {
    case 'sidecar.ping':
      sendResult(req.id, { pong: true });
      return;
    case 'sidecar.info':
      sendResult(req.id, {
        name: SIDECAR_NAME,
        version: SIDECAR_VERSION,
        node: process.version
      });
      return;
    case 'sidecar.listTools':
      sendResult(req.id, Array.from(tools.values()).map((tool) => ({
        name: tool.name,
        description: tool.description,
        parameters: tool.parameters,
        pluginId: tool.pluginId
      })));
      return;
    case 'plugin.load':
      loadPlugin(req);
      return;
    case 'plugin.unload':
      unloadPlugin(req);
      return;
    case 'plugin.callHook':
      callHook(req);
      return;
    case 'channel.deliver':
      deliverChannel(req);
      return;
    case 'tool.call':
      callTool(req);
      return;
    default:
      sendError(req.id, ERROR_METHOD_NOT_FOUND, 'Unbekannte Methode: ' + req.method);
  }
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
  let req;
  try {
    req = JSON.parse(line);
  } catch {
    return; // unparsbar: Zeile ignorieren
  }
  if (typeof req.id !== 'number') {
    return; // Notification (ohne id): nicht beantworten
  }
  try {
    handleRequest(req);
  } catch (err) {
    sendError(req.id, ERROR_INTERNAL, err.message || String(err));
  }
});

// Handshake: Bereitschaft melden, sobald die Event-Loop läuft.
setImmediate(() => {
  send({ jsonrpc: '2.0', method: 'sidecar.ready', params: { name: SIDECAR_NAME, version: SIDECAR_VERSION } });
});