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
// (.ts/.mts/.cts, erasable Syntax, Node >= 22.6). Zusätzlich laufen ESM-Dateien
// (.mjs/.mts sowie .js/.ts unter package.json "type": "module") über einen
// deterministischen ESM-nach-CommonJS-Transform; die SDK-Bare-Specifier
// openclaw/plugin-sdk(,/plugin-entry) und openclaw-plugin(,/channel) werden als
// Laufzeit-Boundary bereitgestellt (nie über node_modules aufgelöst). Beispiel (CommonJS):
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
// export=, enum oder namespace). ESM-Dateien werden vor dem Compile deterministisch nach
// CommonJS übersetzt (siehe Abschnitt "ESM-Module"); SDK-Subpath-Imports lösen über die
// Laufzeit-Boundary auf. Der Modul-Cache ist pro plugin.load frisch, sodass ein
// Hot-Reload (erneutes plugin.load mit derselben id) die Registrierungen neu aufbaut.

const MODULE_EXTENSIONS = ['.js', '.cjs', '.mjs', '.ts', '.mts', '.cts', '.json'];
const TYPE_SCRIPT_EXTENSIONS = ['.ts', '.mts', '.cts'];
// Ehemaliger lexikalischer PROLOGUE (const definePluginEntry=...) entfernt: ein Eintrag, der
// die SDK-Helfer per 'const { definePluginEntry } = require("openclaw/plugin-sdk")' bezieht,
// kollidierte mit der Wrapper-Deklaration ("Identifier has already been declared"). Die Helfer
// sind jetzt globale Eigenschaften (gesetzt in createModuleLoader): sichtbar für CommonJS-Dateien
// ohne import, aber durch eigene Deklarationen überschattbar.

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

// ---- ESM-Module (P4-01 Folgearbeit) ------------------------------------------------
//
// Echte OpenClaw-Bundles werden als ESM-TypeScript ausgeliefert und importieren das SDK
// per Subpath:
//
//   import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
//   export default definePluginEntry({ ... });
//
// Der Sidecar übersetzt solche Dateien deterministisch nach CommonJS (bevor m._compile
// sie ausführt), damit der hermetische require-Scope greifen kann:
//   - Die Modul-Format-Erkennung folgt der Node-Semantik: .mjs/.mts = ESM, .cjs/.cts =
//     CommonJS, .js/.ts = Typ des nächsten package.json (Default CommonJS ohne type).
//   - import/export-Anweisungen auf Top-Level werden auf require/exports umgeschrieben;
//     String-, Template-, Kommentar- und RegExp-Literale bleiben unberührt. Die
//     import-Erkennung endet am Modul-Specifier (ASI-robust), eine abschließende
//     export default funktioniert auch ohne Semikolon.
//   - Die Bare-Specifier openclaw/plugin-sdk (und openclaw/plugin-sdk/plugin-entry) sowie
//     openclaw-plugin (und openclaw-plugin/channel) sind die SDK-Boundary der Laufzeit:
//     sie werden NIEMALS über node_modules aufgelöst, sondern vom Sidecar als schmaler
//     Shim bereitgestellt (definePluginEntry/defineChannelPluginEntry) — analog zu
//     OpenClaw, die das SDK zur Laufzeit injiziert statt es zu installieren.
//   - Nicht umgeschriebene ESM-Features bleiben nativer Syntax: dynamisches import(...)
//     ist in CommonJS erlaubt; import.meta erzeugt (gewollt) einen Syntaxfehler
//     (ERROR_PLUGIN_INVALID). 'export default' ersetzt module.exports — gemischte
//     Default- + Named-Exports in derselben Datei sind nicht unterstützt (CommonJS-Grenze),
//     und ESM-Dateien importieren die SDK-Helfer explizit (CommonJS-Dateien finden sie als
//     globale Eigenschaften der Laufzeit).

function isSdkSpecifier(request) {
  return request === 'openclaw/plugin-sdk'
      || request === 'openclaw/plugin-sdk/plugin-entry'
      || request === 'openclaw-plugin'
      || request === 'openclaw-plugin/channel';
}

// start steht auf einem ' oder "-Literal; Index nach dem schließenden Zeichen (\\-Escapes).
function skipQuotedString(source, start) {
  const quote = source[start];
  let i = start + 1;
  while (i < source.length) {
    if (source[i] === '\\') {
      i += 2;
      continue;
    }
    if (source[i] === quote) {
      return i + 1;
    }
    i++;
  }
  return source.length;
}

// start steht auf einem `; ${...}-Platzhalter mit geschachtelten Klammern werden übersprungen.
function skipTemplateLiteral(source, start) {
  let i = start + 1;
  let depth = 0;
  while (i < source.length) {
    const c = source[i];
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === '`') {
      if (depth === 0) {
        return i + 1;
      }
      i++;
      continue;
    }
    if (c === '$' && source[i + 1] === '{') {
      depth++;
      i += 2;
      continue;
    }
    if (c === '}' && depth > 0) {
      depth--;
      i++;
      continue;
    }
    i++;
  }
  return source.length;
}

function skipLineComment(source, start) {
  let i = start + 2;
  while (i < source.length && source[i] !== '\n') {
    i++;
  }
  return i;
}

function skipBlockComment(source, start) {
  const end = source.indexOf('*/', start + 2);
  return end < 0 ? source.length : end + 2;
}

// start steht auf dem öffnenden '/'; Zeichenklassen [...] zählen nicht als Abschluss.
function skipRegExpLiteral(source, start) {
  let i = start + 1;
  let inClass = false;
  while (i < source.length) {
    const c = source[i];
    if (c === '\\') {
      i += 2;
      continue;
    }
    if (c === '[') {
      inClass = true;
      i++;
      continue;
    }
    if (c === ']') {
      inClass = false;
      i++;
      continue;
    }
    if (c === '/' && !inClass) {
      return i + 1;
    }
    i++;
  }
  return source.length;
}

// Schlüsselwort-Treffer mit Wortgrenze auf beiden Seiten (schlägt nie bei imports/import.meta usw. an).
function keywordAt(source, index, keyword) {
  if (!source.startsWith(keyword, index)) {
    return false;
  }
  const before = index === 0 ? '' : source[index - 1];
  const after = source[index + keyword.length];
  return !/[A-Za-z0-9_$]/.test(before)
      && after !== undefined && !/[A-Za-z0-9_$]/.test(after);
}

// Heuristik, ob an dieser Stelle ein '/' einen RegExp einleitet (statt Division).
function canStartRegExp(prev) {
  if (prev === null) {
    return true;
  }
  return !/[A-Za-z0-9_$)\]'"`]/.test(prev);
}

// Liest ab `start` (Schlüsselwort-Start) eine vollständige import/export-Anweisung ein.
// Ende: ';' in Tiefe 0, für export default-Funktionen/-Klassen ohne Semikolon die
// schließende Klammer des Rumpfs, für import der Modul-Specifier (ASI-robust).
function readEsmStatement(source, start) {
  const n = source.length;
  const isImport = source.startsWith('import', start);
  const endAtBrace = !isImport
      && /^export\s+(default\s+)?((async\s+)?function\b|class\b)/.test(source.slice(start, Math.min(n, start + 80)));
  let i = start;
  let depth = 0;
  while (i < n) {
    const c = source[i];
    if (c === "'" || c === '"') {
      const end = skipQuotedString(source, i);
      if (isImport && depth === 0) {
        return { text: source.slice(start, end), end };
      }
      i = end;
      continue;
    }
    if (c === '`') {
      i = skipTemplateLiteral(source, i);
      continue;
    }
    if (c === '/' && source[i + 1] === '/') {
      i = skipLineComment(source, i);
      continue;
    }
    if (c === '/' && source[i + 1] === '*') {
      i = skipBlockComment(source, i);
      continue;
    }
    if (c === '(') {
      depth++;
      i++;
      continue;
    }
    if (c === '[') {
      depth++;
      i++;
      continue;
    }
    if (c === '{') {
      depth++;
      i++;
      continue;
    }
    if (c === ')') {
      depth--;
      i++;
      continue;
    }
    if (c === ']') {
      depth--;
      i++;
      continue;
    }
    if (c === '}') {
      depth--;
      if (endAtBrace && depth === 0) {
        return { text: source.slice(start, i + 1), end: i + 1 };
      }
      i++;
      continue;
    }
    if (c === ';' && depth === 0) {
      return { text: source.slice(start, i + 1), end: i + 1 };
    }
    i++;
  }
  return { text: source.slice(start, n), end: n };
}

// Zerlegt ein 'a, b as c'-Specifier-Feld in Teile (Tiefe-beachtet) und liefert sie getrimmt.
function splitTopLevelSpecifiers(specs) {
  const parts = [];
  let depth = 0;
  let segStart = 0;
  for (let i = 0; i < specs.length; i++) {
    const c = specs[i];
    if (c === '{' || c === '(' || c === '[') {
      depth++;
    } else if (c === '}' || c === ')' || c === ']') {
      depth--;
    } else if (c === ',' && depth === 0) {
      parts.push(specs.slice(segStart, i));
      segStart = i + 1;
    }
  }
  parts.push(specs.slice(segStart));
  return parts;
}

function specifierDestructure(specs) {
  return splitTopLevelSpecifiers(specs).map((s) => {
    const part = s.trim();
    const as = /\s+as\s+/.exec(part);
    return as
        ? part.slice(0, as.index).trim() + ': ' + part.slice(as.index + as[0].length).trim()
        : part;
  }).join(', ');
}

// 'export const|let|var ...' -> Deklaration + exports-Zuweisungen (nur einfache Identifier).
function rewriteExportDeclarations(kind, decl) {
  const body = decl.replace(/;\s*$/, '');
  const names = [];
  let depth = 0;
  let segStart = 0;
  const parts = [];
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '{' || c === '(' || c === '[') {
      depth++;
    } else if (c === '}' || c === ')' || c === ']') {
      depth--;
    } else if (c === ',' && depth === 0) {
      parts.push(body.slice(segStart, i));
      segStart = i + 1;
    }
  }
  parts.push(body.slice(segStart));
  for (const part of parts) {
    const p = part.trim();
    const eq = p.indexOf('=');
    const head = (eq >= 0 ? p.slice(0, eq) : p).trim();
    if (/^[A-Za-z_$][\w$]*$/.test(head)) {
      names.push(head);
    }
  }
  return kind + ' ' + body + ';'
      + names.map((name) => '\nexports.' + name + ' = ' + name + ';').join('');
}

function rewriteImport(t) {
  let m;
  m = /^import\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    return 'require(' + JSON.stringify(m[1]) + ');';
  }
  m = /^import\s+type\b/.exec(t);
  if (m) {
    return '';
  }
  m = /^import\s*(\w+)\s*,\s*\*\s*as\s+(\w+)\s+from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    const mod = JSON.stringify(m[3]);
    return 'const ' + m[1] + ' = require(' + mod + ');\nconst ' + m[2] + ' = require(' + mod + ');';
  }
  m = /^import\s*(\w+)\s*,\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    const mod = JSON.stringify(m[3]);
    return 'const ' + m[1] + ' = require(' + mod + ');\nconst { ' + specifierDestructure(m[2]) + ' } = require(' + mod + ');';
  }
  m = /^import\s*\*\s*as\s+(\w+)\s+from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    return 'const ' + m[1] + ' = require(' + JSON.stringify(m[2]) + ');';
  }
  m = /^import\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    return 'const { ' + specifierDestructure(m[1]) + ' } = require(' + JSON.stringify(m[2]) + ');';
  }
  m = /^import\s*(\w+)\s+from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    return 'const ' + m[1] + ' = require(' + JSON.stringify(m[2]) + ');';
  }
  // Nicht unterstütztes import-Formular: nativer Syntaxfehler beim Compile ist gewollt.
  return t;
}

function rewriteExport(t) {
  let m;
  m = /^export\s+default\s+([\s\S]*)$/.exec(t);
  if (m) {
    return 'module.exports = ' + m[1] + ';';
  }
  m = /^export\s+(const|let|var)\s+([\s\S]+)$/.exec(t);
  if (m) {
    return rewriteExportDeclarations(m[1], m[2]);
  }
  m = /^export\s+(async\s+)?(function\b[\s\S]*)$/.exec(t);
  if (m) {
    const body = (m[1] || '') + m[2];
    const fnName = /\bfunction\b\s*([A-Za-z_$][\w$]*)/.exec(m[2]);
    return body + (fnName ? '\nexports.' + fnName[1] + ' = ' + fnName[1] + ';' : '');
  }
  m = /^export\s+(class\b[\s\S]*)$/.exec(t);
  if (m) {
    const clsName = /\bclass\s*([A-Za-z_$][\w$]*)/.exec(m[1]);
    return m[1] + (clsName ? '\nexports.' + clsName[1] + ' = ' + clsName[1] + ';' : '');
  }
  m = /^export\s*\*\s*as\s+(\w+)\s+from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    return 'exports.' + m[1] + ' = require(' + JSON.stringify(m[2]) + ');';
  }
  m = /^export\s*\{([\s\S]*?)\}\s*from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    const mod = 'require(' + JSON.stringify(m[2]) + ')';
    // Block-Scope, damit mehrere Re-Exports in derselben Datei nicht um __mod konkurrieren.
    return '{ const __mod = ' + mod + ';\n'
        + splitTopLevelSpecifiers(m[1]).map((s) => {
          const part = s.trim();
          const as = /\s+as\s+/.exec(part);
          const from = as ? part.slice(0, as.index).trim() : part;
          const to = as ? part.slice(as.index + as[0].length).trim() : part;
          // 'default' ist über scopedRequire bereits entwaffnet (esmodule-Interop).
          const value = from === 'default' ? '__mod' : '__mod.' + from;
          return 'exports.' + to + ' = ' + value + ';';
        }).join('\n') + ' }';
  }
  m = /^export\s*\*\s*from\s*['"]([^'"]+)['"]\s*;?$/.exec(t);
  if (m) {
    return '{ const __mod = require(' + JSON.stringify(m[1]) + ');'
        + '\nfor (const __k of Object.keys(__mod)) { if (__k !== "default") { exports[__k] = __mod[__k]; } } }';
  }
  m = /^export\s*\{([\s\S]*?)\}\s*;?\s*$/.exec(t);
  if (m) {
    return splitTopLevelSpecifiers(m[1]).map((s) => {
      const part = s.trim();
      const as = /\s+as\s+/.exec(part);
      const from = as ? part.slice(0, as.index).trim() : part;
      const to = as ? part.slice(as.index + as[0].length).trim() : part;
      return 'exports.' + to + ' = ' + from + ';';
    }).join('\n');
  }
  // z. B. export type ... (nach Type-Stripping normalerweise bereits entfernt) oder
  // nicht unterstützte Formen: nativer Syntaxfehler beim Compile ist gewollt.
  return t;
}

function rewriteEsmStatement(text) {
  const t = text.trim();
  if (t.startsWith('import')) {
    if (t.length > 6 && (t[6] === '(' || t[6] === '.')) {
      return text; // dynamisches import(...) / import.meta: nicht umschreiben
    }
    return rewriteImport(t);
  }
  if (t.startsWith('export')) {
    return rewriteExport(t);
  }
  return text;
}

function convertEsmToCjs(filename, source) {
  const n = source.length;
  let out = '';
  let i = 0;
  let depth = 0;
  let lastSig = null;
  while (i < n) {
    const c = source[i];
    if (c === "'" || c === '"') {
      const end = skipQuotedString(source, i);
      out += source.slice(i, end);
      i = end;
      lastSig = '"';
      continue;
    }
    if (c === '`') {
      const end = skipTemplateLiteral(source, i);
      out += source.slice(i, end);
      i = end;
      lastSig = '`';
      continue;
    }
    if (c === '/' && source[i + 1] === '/') {
      const end = skipLineComment(source, i);
      out += source.slice(i, end);
      i = end;
      continue;
    }
    if (c === '/' && source[i + 1] === '*') {
      const end = skipBlockComment(source, i);
      out += source.slice(i, end);
      i = end;
      continue;
    }
    if (c === '/' && canStartRegExp(lastSig)) {
      const end = skipRegExpLiteral(source, i);
      out += source.slice(i, end);
      i = end;
      lastSig = '/';
      continue;
    }
    if (c === '(' || c === '[' || c === '{') {
      depth++;
      out += c;
      lastSig = c;
      i++;
      continue;
    }
    if (c === ')' || c === ']' || c === '}') {
      depth = Math.max(0, depth - 1);
      out += c;
      lastSig = c;
      i++;
      continue;
    }
    if (depth === 0 && keywordAt(source, i, 'import')) {
      const stmt = readEsmStatement(source, i);
      out += rewriteEsmStatement(stmt.text);
      i = stmt.end;
      continue;
    }
    if (depth === 0 && keywordAt(source, i, 'export')) {
      const stmt = readEsmStatement(source, i);
      out += rewriteEsmStatement(stmt.text);
      i = stmt.end;
      continue;
    }
    out += c;
    if (c !== ' ' && c !== '\t' && c !== '\n' && c !== '\r') {
      lastSig = c;
    }
    i++;
  }
  return out;
}

/**
 * Erzeugt den hermetischen Modul-Loader eines Plugin-Bundles.
 * Liefert { evaluateModule(file), resolveRequest(request) }; der Modul-Cache ist pro
 * Load frisch (Hot-Reload: jedes plugin.load wertet das Entry neu aus).
 */
function createModuleLoader(pluginId, baseDir) {
  const base = path.resolve(baseDir);
  const cache = new Map();               // Datei -> Modul (pro Load frisch)
  const dirCache = new Map();            // Verzeichnis -> package.json-type (Erkennung)
  // SDK-Boundary: schmaler Shim, der die OpenClaw-Entry-Helfer bereitstellt. Wird für die
  // Bare-Specifier openclaw/plugin-sdk(, /plugin-entry) und openclaw-plugin(, /channel)
  // zurückgegeben; nie über node_modules aufgelöst (siehe §ESM-Module).
  const sdkModule = {
    filename: '<openclaw-sdk>',
    exports: {
      definePluginEntry: (entry) => entry,
      defineChannelPluginEntry: (entry) => entry
    }
  };
  // Als globale Eigenschaften sichtbar (keine lexikalischen Wrapper-Deklarationen), damit
  // CommonJS-Dateien die Helfer auch ohne import nutzen können, während ein eigener
  // 'const { definePluginEntry } = require("openclaw/plugin-sdk")' sie sauber überschattet.
  globalThis.definePluginEntry = sdkModule.exports.definePluginEntry;
  globalThis.defineChannelPluginEntry = sdkModule.exports.defineChannelPluginEntry;

  // Modul-Format-Erkennung nach Node-Semantik (nächstes package.json "type" entscheidet
  // für .js/.ts; .mjs/.mts = ESM, .cjs/.cts = CommonJS).
  function packageJsonType(dir) {
    if (!dirCache.has(dir)) {
      let type = 'none';
      const pkg = path.join(dir, 'package.json');
      if (statIs(pkg, 'file')) {
        try {
          type = JSON.parse(fs.readFileSync(pkg, 'utf8')).type === 'module' ? 'module' : 'commonjs';
        } catch {
          type = 'commonjs';
        }
      }
      dirCache.set(dir, type);
    }
    return dirCache.get(dir);
  }

  function moduleFormat(filename) {
    if (filename.endsWith('.mjs') || filename.endsWith('.mts')) {
      return 'esm';
    }
    if (filename.endsWith('.cjs') || filename.endsWith('.cts')) {
      return 'commonjs';
    }
    let dir = path.dirname(filename);
    while (true) {
      const type = packageJsonType(dir);
      if (type === 'module') {
        return 'esm';
      }
      if (type !== 'none') {
        return 'commonjs';
      }
      if (dir === base) {
        return 'commonjs';
      }
      const parent = path.dirname(dir);
      if (parent === dir || !isInside(base, parent)) {
        return 'commonjs';
      }
      dir = parent;
    }
  }

  function resolveRequest(request, fromFile) {
    if (isBuiltin(request)) {
      return request;
    }
    if (isSdkSpecifier(request)) {
      return sdkModule;
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
    const esm = moduleFormat(filename) === 'esm';
    if (esm) {
      code = convertEsmToCjs(filename, code);
    }
    // ESM-Dateien importieren die SDK-Helfer selbst; CommonJS-Dateien finden sie als globale
    // Eigenschaften (siehe createModuleLoader). Eine doppelte Deklaration wäre ein Syntaxfehler.
    m._compile('\n' + code, filename);
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
    if (resolved === sdkModule) {
      return sdkModule.exports; // SDK-Boundary: von der Laufzeit bereitgestellt
    }
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
    if (resolved === sdkModule) {
      return request;
    }
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