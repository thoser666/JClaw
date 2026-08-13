// JClaw Referenz-Sidecar für das Bridge-Protokoll (P1-03, siehe docs/bridge-protocol.md).
//
// Erwartet JSON-RPC-2.0-Nachrichten als Newline-delimited JSON auf stdin und antwortet
// auf stdout. Nach dem Start sendet das Sidecar eine `sidecar.ready`-Notification als
// Handshake. Tools werden über `tool.call` aufgerufen; das Registrieren echter Plugins
// (definePluginEntry) folgt in P4-01.
//
// Fehlercodes: siehe NodeSidecarBridge (ERROR_*).

'use strict';

const readline = require('readline');

const ERROR_METHOD_NOT_FOUND = -32601;
const ERROR_TOOL_NOT_FOUND = -32001;
const ERROR_TOOL_EXECUTION = -32002;
const ERROR_INTERNAL = -32003;

const SIDECAR_NAME = 'jclaw-protocol-sidecar';
const SIDECAR_VERSION = '1.0.0';

// Referenz-Tools. `run` wirft bei ungültigen Argumenten -> ERROR_TOOL_EXECUTION.
const tools = {
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
      // Synchron: blockiert die Event-Loop, bis die Wartezeit abgelaufen ist. Ein zu
      // langsames Tool führt im Java-Kern zu einem Call-Timeout (ERROR_TIMEOUT) -
      // genau das Verhalten, das die Bridge-Tests absichern.
      const sab = new SharedArrayBuffer(4);
      Atomics.wait(new Int32Array(sab), 0, 0, ms);
      return { sleptMs: ms };
    }
  }
};

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function sendResult(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

function callTool(req) {
  const name = req.params && req.params.name;
  const args = (req.params && req.params.arguments) || {};
  const tool = tools[name];
  if (!tool) {
    sendError(req.id, ERROR_TOOL_NOT_FOUND, 'Unbekanntes Tool: ' + name);
    return;
  }
  try {
    sendResult(req.id, tool.run(args));
  } catch (err) {
    sendError(req.id, ERROR_TOOL_EXECUTION, err.message || String(err));
  }
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
      sendResult(req.id, Object.keys(tools).map((name) => ({
        name,
        description: tools[name].description,
        parameters: tools[name].parameters
      })));
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
