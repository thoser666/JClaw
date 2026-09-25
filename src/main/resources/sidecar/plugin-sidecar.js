// JClaw Plugin-Sidecar für die Plugin-Laufzeit (P4-01, siehe docs/bridge-protocol.md §8).
//
// Basierend auf protocol-sidecar.js: gleiche JSON-RPC-2.0-Framing-Regeln (NDJSON über
// stdio, sidecar.ready-Handshake, sidecar.ping/info/listTools/tool.call). Zusätzlich
// registrieren Plugins ihre Tools/Commands/Hooks zur Laufzeit über die OpenClaw-Entry-
// Semantik (definePluginEntry / defineChannelPluginEntry).
//
// Entry-Vertrag (CommonJS, ohne npm/TypeScript — reine Referenz-Laufzeit):
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

const ERROR_METHOD_NOT_FOUND = -32601;
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

const HOOK_EVENTS = [
  'before_model_resolve', 'before_prompt_build', 'before_agent_run',
  'before_agent_reply', 'before_agent_finalize', 'agent_end',
  'before_tool_call', 'after_tool_call', 'tool_result_persist',
  'message_received', 'message_sending', 'message_sent', 'reply_payload_sending',
  'session_start', 'session_end',
  'gateway_start', 'gateway_stop', 'cron_reconciled', 'cron_changed',
  'before_install',
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
      const hook = { pluginId: id, event, handler, matcher, priority };
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

function loadPlugin(req) {
  const id = req.params && req.params.id;
  const source = req.params && req.params.source;
  if (typeof id !== 'string' || id.length === 0 || typeof source !== 'string' || source.length === 0) {
    sendError(req.id, ERROR_PLUGIN_INVALID, 'plugin.load benötigt id (String) und source (String).');
    return;
  }

  if (plugins.has(id)) {
    unloadById(id); // Erneut laden (Hot-Reload-Semantik): alte Registrierungen entfernen
  }

  let entry;
  try {
    entry = evaluateEntry(id, source);
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
    hooks: plugin.hooks.map((hook) => ({ event: hook.event, priority: hook.priority })),
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