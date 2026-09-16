# Bridge-Protokoll: Java-Kern ↔ Node-Sidecar

- Status: **Festgelegt (P1-03)**
- Datum: 2026-08-13
- Bezug: [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md) · [Paritäts-Roadmap](parity-roadmap.md) P1-03
- Implementierung: `biz.brumm.infrastructure.sidecar.*` · Referenz-Sidecar: `src/main/resources/sidecar/protocol-sidecar.js`

## 1. Überblick

Der Java-Kern kommuniziert mit einem externen **Node.js-Sidecar-Prozess** über **JSON-RPC 2.0**, Newline-delimited über **stdio**. Der Sidecar ist die Plugin-Laufzeit (P4-01: `definePluginEntry`/`defineChannelPluginEntry`, siehe §8); die Bridge selbst bleibt generisch, damit sie auch für Hooks oder MCP-Skripte nutzbar bleibt.

```
┌────────────────────┐   JSON-RPC 2.0 / NDJSON über stdio   ┌──────────────────────┐
│  Java-Kern         │ ──────────── stdin ─────────────────► │  Node.js-Sidecar     │
│  NodeSidecarBridge │ ◄─────────── stdout ───────────────── │  (separater Prozess) │
└────────────────────┘                                        └──────────────────────┘
```

## 2. Transport

| Aspekt | Festlegung |
|---|---|
| Protokoll | JSON-RPC 2.0 |
| Framing | **Newline-delimited JSON (NDJSON)** — genau eine Nachricht pro Zeile, abgeschlossen mit `\n` |
| Kanäle | Anfragen/Antworten über **stdin/stdout**; stderr nur für Logs (wird gedrained, nie geparst) |
| Zeichenkodierung | UTF-8 |
| IDs | Numerisch, vom Kern vergeben, monoton steigend; Notifications ohne gültige id (`id < 0`) |
| Zeilenlänge | Kein hartes Limit; ein Frame = eine Zeile |

## 3. Nachrichtenmodell

Alle Nachrichten sind JSON-Objekte mit `jsonrpc: "2.0"`.

### Anfrage
```json
{ "jsonrpc": "2.0", "id": 1, "method": "sidecar.ping", "params": { } }
```

### Antwort (Erfolg)
```json
{ "jsonrpc": "2.0", "id": 1, "result": { "pong": true } }
```

### Antwort (Fehler) — strukturiertes Fehlerobjekt
```json
{ "jsonrpc": "2.0", "id": 1, "error": { "code": -32001, "message": "Unbekanntes Tool: foo" } }
```

### Notification (keine id → keine Antwort)
```json
{ "jsonrpc": "2.0", "method": "sidecar.ready", "params": { "name": "jclaw-protocol-sidecar", "version": "1.0.0" } }
```

## 4. Methoden-Katalog

### `sidecar.ready` (Notification, vom Sidecar gesendet)

Handshake: Der Sidecar sendet die Notification, sobald seine Event-Loop läuft. Der Kern wartet darauf nach dem Prozessstart (Ready-Timeout).

- `params`: `{ "name": string, "version": string }`

### `sidecar.ping` → `{ "pong": true }`

Lebendigkeitsprüfung; liefert `true`, wenn die Antwort `pong` enthält.

### `sidecar.info` → `{ "name", "version", "node" }`

Metadaten des Sidecars (`node` = `process.version`).

### `sidecar.listTools` → `[ ToolDescriptor ]`

Registrierte Tools des Sidecars. Ein `ToolDescriptor`:

```json
{
  "name": "add",
  "description": "Berechnet die Summe zweier Zahlen a + b.",
  "parameters": {
    "type": "object",
    "properties": { "a": { "type": "number", "description": "Erster Summand." }, "b": { "type": "number", "description": "Zweiter Summand." } },
    "required": ["a", "b"]
  }
}
```

`parameters` ist das Argument-Schema im JSON-Schema-Stil und wird in P4-01 als Basis für das Tool-Calling des Modells verwendet. In P1-03 liest der Kern nur `name` und `description`; `parameters` wird als roher Knoten transportiert (`SidecarToolDescriptor.parameters()`).

### `tool.call`

- `params`: `{ "name": string, "arguments": object }`
- Erfolg: `result` = das Tool-Ergebnis (frei definiertes JSON, z. B. `{ "result": 5 }`).
- Fehler: siehe Fehlercodes.

Referenz-Tools des Sidecars (P1-03): `add` (`a`+`b`), `echo` (`text`), `sleep` (`ms`, blockiert synchron → dient dem Timeout-Test).

## 5. Fehlercodes

Konstanten in `NodeSidecarBridge` (`ERROR_*`):

| Code | Konstante | Bedeutung |
|---|---|---|
| `-32601` | `ERROR_METHOD_NOT_FOUND` | Unbekannte RPC-Methode |
| `-32602` | *(Kern)* | Ungültige Params (Reserviert, im Referenz-Sidecar nicht belegt) |
| `-32001` | `ERROR_TOOL_NOT_FOUND` | `tool.call` mit unbekanntem Tool-Namen |
| `-32002` | `ERROR_TOOL_EXECUTION` | Fehler in der Tool-Ausführung (`run` warf) |
| `-32003` | `ERROR_INTERNAL` | Interner Sidecar-Fehler beim Bearbeiten der Anfrage |
| `-32004` | `ERROR_TIMEOUT` | Vom **Kern** erzeugt (kein Sidecar-Fehler) — Aufruf überschritt das Call-Timeout |

`-32004` ist kein JSON-RPC-Standardcode; er wird in `SidecarTimeoutException` übersetzt. Sidecar-seitige Fehler werden als `SidecarCallException(code, message, method)` geworfen.

## 6. Lebenszyklus

### Start
1. Kern startet `node -e <script>` (Referenz-Script über `NodeSidecarBridge.defaultScript()`).
2. Kern wartet auf die `sidecar.ready`-Notification (Ready-Timeout, Default **5 s**).
3. Läuft das Timeout ab → Prozess wird beendet, `SidecarTimeoutException`.

### Aufruf
1. Kern sendet Request mit neuer id über stdin.
2. Reader-Thread des Kerns entscheidet Antworten über die id; Notifications/Requests des Sidecars werden nicht als Antworten behandelt.
3. Erfolg → `result`; strukturierter Fehler → `SidecarCallException`; keine Antwort in **15 s** (Default Call-Timeout) → `SidecarTimeoutException`.

### Neustart (`restart()`)
Beendet den laufenden Prozess (Graceful-Shutdown, 5 s, danach `destroyForcibly()`), startet einen neuen Prozess inkl. Handshake. In-flight-Aufrufe scheitern mit `IOException`.

### Schließen (`close()`)
Schließt stdin (→ Sidecar beendet sich selbst), destruiert bei Bedarf den Prozess (5 s, dann `destroyForcibly()`). Idempotent; Aufrufe danach scheitern sofort mit `IOException`.

### Crash-Verhalten
Endet die stdout-Ausgabe ohne `close()` (Prozess gecrasht), werden alle in-flight-Aufrufe mit `IOException` beendet und ein WARN geloggt. **Kein** automatischer Neustart — dieser erfolgt explizit über `restart()`.

## 7. Java-Referenz

| Baustein | Zweck |
|---|---|
| `NodeSidecarBridge` | Verwaltete Bridge: `start(...)`, `ping()`, `info()`, `listTools()`, `callTool(name, args)`, `loadPlugin(id, source)`, `unloadPlugin(id)`, `restart()`, `close()` |
| `JsonRpcMessage` | Nachrichtenmodell (Request/Response/Error/Notification, strukturierte Fehler) |
| `JsonRpcLineCodec` | NDJSON-Encoding/-Decoding (reines Framing, unit-getestet) |
| `SidecarCallException` | Sidecar-Fehler mit JSON-RPC-Fehlercode |
| `SidecarTimeoutException` | Call-/Ready-Timeout |
| `SidecarToolDescriptor` | Tool-Registrierung (`name`, `description`, `parameters`) |
| `NodeSidecarPluginRuntime` | Plugin-Laufzeit (P4-01): `load(Plugin)` → Quittung (Tools/Commands/Hooks), `unload(id)`, `loadAvailable()`, `tools()`, `callTool(...)`, `close()` |
| `EntryPointResolver` | Entry-Auflösung eines Bundles (`package.json` → `main`, Traversal-Schutz, dann `src/index.js` … `main.js`) |

Testabdeckung (P1-03): `JsonRpcLineCodecTest` (Codec/Framing) und `NodeSidecarBridgeTest` (Integration mit echtem Node.js; übersprungen, wenn Node nicht verfügbar). Abgedeckt: Handshake, ping/info/listTools, Tool-Aufruf (Erfolg + Fehler), Method-NotFound, Call-Timeout, Restart (neue PID), Close, Aufruf nach Close/Restart-nach-Close.

Starten: `NodeSidecarBridge.start(ObjectMapper)` startet das Referenz-Sidecar (`protocol-sidecar.js`); `NodeSidecarBridge.pluginScript()` liefert das Plugin-Runtime-Sidecar (`plugin-sidecar.js`). Das Script wird in eine **temporäre Datei** geschrieben und als `<file>`-Argument gestartet — `-e`-Argumente unterliegen auf Windows der `CreateProcess`-Längenbegrenzung (~8191 Zeichen) und größere Scripts starteten sonst nicht (behoben mit P4-01).

## 8. Plugin-Laufzeit (P4-01)

Das Plugin-Runtime-Sidecar (`sidecar/plugin-sidecar.js`) stellt die OpenClaw-Entry-Semantik bereit: Plugins registrieren ihre **Tools, Commands und Hooks zur Laufzeit** (statt statisch) über `definePluginEntry` (Agent-Plugins) bzw. `defineChannelPluginEntry` (Channel-Plugins).

### Entry-Vertrag (Referenz-Laufzeit)

Die Plugin-Source ist ein **CommonJS-Entry** ohne npm/TypeScript-Abhängigkeiten; die Kontrakte `definePluginEntry`/`defineChannelPluginEntry` stellt das Sidecar als Globals in einer `vm`-Sandbox bereit:

```js
module.exports = definePluginEntry({
  id: 'acme/demo',
  name: 'Demo',
  register(api) {
    api.registerTool({
      name: 'greet',
      description: 'Begrüßt jemanden.',
      parameters: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] },
      execute(args) { return { greeting: 'Hallo ' + args.name }; }
    });
    api.registerCommand({ name: 'demo-help', description: 'Slash-Command.', execute(args) { return { ok: true }; } });
    api.on('before_tool_call', (ctx) => {
      if (ctx.arguments && ctx.arguments.name === 'block') throw new Error('gesperrt');
    }, { matcher: 'greet', priority: 10 });
  }
});
```

`register(api)` erhält: `registerTool({name, description, parameters, outputSchema, execute})`, `registerCommand({name, description, execute})` und `on(event, handler, {matcher, priority})`. Hooks werden in absteigender `priority` ausgeführt; `matcher` ist ein Name-String, RegExp oder `undefined` (alle Tools). `before_tool_call`/`after_tool_call` werden beim `tool.call` im Sidecar ausgeführt — ein werfender Handler blockiert den Aufruf bzw. das Ergebnis mit **`ERROR_HOOK_BLOCKED` (-32005)**.

### Methoden

| Methode | Params | Ergebnis |
|---|---|---|
| `plugin.load` | `{id, source}` | Quittung `{id, name, tools:[{name, description, parameters}], commands:[String], hooks:[{event, priority}]}`; ESM-transpilierte Sources (`exports.default`) werden aufgelöst; `plugin` in vm-Sandbox ohne Zugriff auf `require`/`process` |
| `plugin.unload` | `{id}` | `{id, removed}` — entfernt Tools/Commands/Hooks des Plugins (statische Referenz-Tools werden wiederhergestellt) |

`sidecar.listTools` liefert Referenz- + Plugin-Tools (Plugin-Tools mit `pluginId`). `tool.call` dispatched über die kombinierte Registry. Fehler: `ERROR_HOOK_BLOCKED` (-32005), `ERROR_PLUGIN_INVALID` (-32006, z. B. fehlende `definePluginEntry`/`register` oder Fehler in `register()`).

### Entry-Auflösung (Java)

`EntryPointResolver` löst den Entry-Punkt eines Bundles auf: `package.json` → `main` (nur innerhalb des Plugin-Ordners, **Traversal-Schutz**), sonst Fallbacks `src/index.js`, `src/index.mjs`, `index.js`, `index.mjs`, `main.js`. Ohne Entry bleibt das Plugin Control-Plane-only (`load()` liefert `Optional.empty()`).

### Laufzeit-Wiring

`NodeSidecarPluginRuntime` (`@Component`, aktiv bei `jclaw.agent.plugins.runtime.enabled=true`, Deny-by-Default) startet den Node-Sidecar lazy, lädt über `load()`/`loadAvailable()` alle gültigen OpenClaw-Plugins mit Entry-Point und hält die Load-Quittungen. Tools sind über `tools()`/`callTool()` erreichbar; `close()` beendet den Sidecar-Prozess.

## 9. Verbleibende offene Punkte (nach P4-01)

- **Tool-Schema → Spring-AI:** `parameters` (JSON-Schema) bislang roher Knoten (`SidecarToolDescriptor.parameters()`); Anbindung an das Spring-AI-Tool-Calling (`@Tool`, `JsonSchema`) steht aus.
- **npm/TypeScript-Bundles:** `definePluginEntry`-Shim + CommonJS sind die **Referenz-Laufzeit**; echte OpenClaw-Bundles (ESM-TypeScript, `openclaw/plugin-sdk`-Imports) benötigen npm-Auflösung/Bundling — gegen den neuen SDK-Stand (Subpath-Imports, moderne Hook-Stages, `setup`-Deskriptoren).
- **Hooks/Channels:** Nur `before_tool_call`/`after_tool_call` werden ausgeführt; weitere Lifecycle-Events (P1-11) und die Channel-Runtime (`defineChannelPluginEntry`, Empfang) laufen über dieselbe Bridge, Ausführung folgt.
- **Backpressure/Parallelität:** Bisher eine Antwort pro Request (id-basiert); keine Limits für gleichzeitige Aufrufe definiert.
- **Stderr-Auswertung:** Bisher nur Log; bei Startfehlern (fehlendes npm-Modul) könnte stderr gezielt in die Fehlermeldung fließen.
