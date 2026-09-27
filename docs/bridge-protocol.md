# Bridge-Protokoll: Java-Kern â†” Node-Sidecar

- Status: **Festgelegt (P1-03)**
- Datum: 2026-08-13
- Bezug: [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md) Â· [ParitÃ¤ts-Roadmap](parity-roadmap.md) P1-03
- Implementierung: `biz.brumm.infrastructure.sidecar.*` Â· Referenz-Sidecar: `src/main/resources/sidecar/protocol-sidecar.js`

## 1. Ãœberblick

Der Java-Kern kommuniziert mit einem externen **Node.js-Sidecar-Prozess** Ã¼ber **JSON-RPC 2.0**, Newline-delimited Ã¼ber **stdio**. Der Sidecar ist die Plugin-Laufzeit (P4-01: `definePluginEntry`/`defineChannelPluginEntry`, siehe Â§8); die Bridge selbst bleibt generisch, damit sie auch fÃ¼r Hooks oder MCP-Skripte nutzbar bleibt.

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   JSON-RPC 2.0 / NDJSON Ã¼ber stdio   â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚  Java-Kern         â”‚ â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ stdin â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º â”‚  Node.js-Sidecar     â”‚
â”‚  NodeSidecarBridge â”‚ â—„â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ stdout â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ â”‚  (separater Prozess) â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜                                        â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

## 2. Transport

| Aspekt | Festlegung |
|---|---|
| Protokoll | JSON-RPC 2.0 |
| Framing | **Newline-delimited JSON (NDJSON)** â€” genau eine Nachricht pro Zeile, abgeschlossen mit `\n` |
| Kanäle | Anfragen/Antworten über **stdin/stdout**; stderr nur für Logs (wird gedrained, nie als Protokoll geparst) — bei Start-/Crashfehlern fließen die gesammelten stderr-Zeilen in die Fehlermeldung |
| Zeichenkodierung | UTF-8 |
| IDs | Numerisch, vom Kern vergeben, monoton steigend; Notifications ohne gÃ¼ltige id (`id < 0`) |
| ZeilenlÃ¤nge | Kein hartes Limit; ein Frame = eine Zeile |

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

### Antwort (Fehler) â€” strukturiertes Fehlerobjekt
```json
{ "jsonrpc": "2.0", "id": 1, "error": { "code": -32001, "message": "Unbekanntes Tool: foo" } }
```

### Notification (keine id â†’ keine Antwort)
```json
{ "jsonrpc": "2.0", "method": "sidecar.ready", "params": { "name": "jclaw-protocol-sidecar", "version": "1.0.0" } }
```

## 4. Methoden-Katalog

### `sidecar.ready` (Notification, vom Sidecar gesendet)

Handshake: Der Sidecar sendet die Notification, sobald seine Event-Loop lÃ¤uft. Der Kern wartet darauf nach dem Prozessstart (Ready-Timeout).

- `params`: `{ "name": string, "version": string }`

### `sidecar.ping` â†’ `{ "pong": true }`

LebendigkeitsprÃ¼fung; liefert `true`, wenn die Antwort `pong` enthÃ¤lt.

### `sidecar.info` â†’ `{ "name", "version", "node" }`

Metadaten des Sidecars (`node` = `process.version`).

### `sidecar.listTools` â†’ `[ ToolDescriptor ]`

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

`parameters` ist das Argument-Schema im JSON-Schema-Stil und wird in P4-01 als Basis fÃ¼r das Tool-Calling des Modells verwendet. In P1-03 liest der Kern nur `name` und `description`; `parameters` wird als roher Knoten transportiert (`SidecarToolDescriptor.parameters()`).

### `tool.call`

- `params`: `{ "name": string, "arguments": object }`
- Erfolg: `result` = das Tool-Ergebnis (frei definiertes JSON, z. B. `{ "result": 5 }`).
- Fehler: siehe Fehlercodes.

Referenz-Tools des Sidecars (P1-03): `add` (`a`+`b`), `echo` (`text`), `sleep` (`ms`, blockiert synchron â†’ dient dem Timeout-Test).

## 5. Fehlercodes

Konstanten in `NodeSidecarBridge` (`ERROR_*`):

| Code | Konstante | Bedeutung |
|---|---|---|
| `-32601` | `ERROR_METHOD_NOT_FOUND` | Unbekannte RPC-Methode |
| `-32602` | *(Kern)* | UngÃ¼ltige Params (Reserviert, im Referenz-Sidecar nicht belegt) |
| `-32001` | `ERROR_TOOL_NOT_FOUND` | `tool.call` mit unbekanntem Tool-Namen |
| `-32002` | `ERROR_TOOL_EXECUTION` | Fehler in der Tool-AusfÃ¼hrung (`run` warf) |
| `-32003` | `ERROR_INTERNAL` | Interner Sidecar-Fehler beim Bearbeiten der Anfrage |
| `-32004` | `ERROR_TIMEOUT` | Vom **Kern** erzeugt (kein Sidecar-Fehler) â€” Aufruf Ã¼berschritt das Call-Timeout |
| `-32009` | `ERROR_BUSY` | Vom **Kern** erzeugt (Backpressure) â€” maximal `DEFAULT_MAX_CONCURRENT_REQUESTS` gleichzeitige In-Flight-Requests; kein Slot innerhalb des Wartefensters frei |

`-32004` ist kein JSON-RPC-Standardcode; er wird in `SidecarTimeoutException` Ã¼bersetzt. Sidecar-seitige Fehler werden als `SidecarCallException(code, message, method)` geworfen. `-32009` (`ERROR_BUSY`) wird ebenfalls als `SidecarCallException` geworfen â€” Backpressure des Kerns, wenn mehr gleichzeitige Aufrufe eintreffen als erlaubt (siehe Â§6).

## 6. Lebenszyklus

### Start
1. Kern startet `node -e <script>` (Referenz-Script Ã¼ber `NodeSidecarBridge.defaultScript()`).
2. Kern wartet auf die `sidecar.ready`-Notification (Ready-Timeout, Default **5 s**).
3. LÃ¤uft das Timeout ab â†’ Prozess wird beendet, `SidecarTimeoutException`. Die bis dahin auf stderr gesammelten Zeilen (z. B. fehlendes Modul, Syntaxfehler) werden in die Fehlermeldung einbezogen (max. 20 Zeilen).

### Aufruf
1. Kern sendet Request mit neuer id Ã¼ber stdin.
2. Reader-Thread des Kerns entscheidet Antworten Ã¼ber die id; Notifications/Requests des Sidecars werden nicht als Antworten behandelt.
3. Erfolg â†’ `result`; strukturierter Fehler â†’ `SidecarCallException`; keine Antwort in **15 s** (Default Call-Timeout) â†’ `SidecarTimeoutException`.
4. **Backpressure:** Maximal `DEFAULT_MAX_CONCURRENT_REQUESTS` (**8**) gleichzeitige In-Flight-Requests (fairer Semaphore, FIFO). Ist das Limit erreicht, wartet ein neuer Aufruf bis zu `DEFAULT_BACKPRESSURE_WAIT_MILLIS` (**5 s**) auf einen freien Slot â€” danach `SidecarCallException` mit `ERROR_BUSY` (-32009) statt unbeschrÃ¤nkter Queue. Konfigurierbar Ã¼ber `start(..., maxConcurrentRequests, backpressureWaitMillis)`.

### Neustart (`restart()`)
Beendet den laufenden Prozess (Graceful-Shutdown, 5 s, danach `destroyForcibly()`), startet einen neuen Prozess inkl. Handshake. In-flight-Aufrufe scheitern mit `IOException`.

### SchlieÃŸen (`close()`)
SchlieÃŸt stdin (â†’ Sidecar beendet sich selbst), destruiert bei Bedarf den Prozess (5 s, dann `destroyForcibly()`). Idempotent; Aufrufe danach scheitern sofort mit `IOException`.

### Crash-Verhalten
Endet die stdout-Ausgabe ohne `close()` (Prozess gecrasht), werden alle in-flight-Aufrufe mit `IOException` beendet und ein WARN geloggt; die gesammelten stderr-Zeilen (max. 20) werden in die `IOException` einbezogen. **Kein** automatischer Neustart â€” dieser erfolgt explizit Ã¼ber `restart()`.

## 7. Java-Referenz

| Baustein | Zweck |
|---|---|
| `NodeSidecarBridge` | Verwaltete Bridge: `start(...)`, `ping()`, `info()`, `listTools()`, `callTool(name, args)`, `loadPlugin(id, source)`, `unloadPlugin(id)`, `deliverChannelMessage(channel, message)`, `restart()`, `close()`; Backpressure (Semaphore): `DEFAULT_MAX_CONCURRENT_REQUESTS`=8, `DEFAULT_BACKPRESSURE_WAIT_MILLIS`=5 s, `ERROR_BUSY` bei Ãœberlast; sammelt stderr (max. 20 Zeilen) pro Prozesslauf und bettet es bei Start-/Crashfehlern in die Fehlermeldung ein |
| `JsonRpcMessage` | Nachrichtenmodell (Request/Response/Error/Notification, strukturierte Fehler) |
| `JsonRpcLineCodec` | NDJSON-Encoding/-Decoding (reines Framing, unit-getestet) |
| `SidecarCallException` | Sidecar-Fehler mit JSON-RPC-Fehlercode |
| `SidecarTimeoutException` | Call-/Ready-Timeout; wahlweise mit stderr-Diagnose (Startfehler: fehlendes Modul, Syntaxfehler) |
| `SidecarToolDescriptor` | Tool-Registrierung (`name`, `description`, `parameters`) |
| `NodeSidecarPluginRuntime` | Plugin-Laufzeit (P4-01): `load(Plugin)` â†’ Quittung (Tools/Commands/Channels/Hooks), `unload(id)`, `loadAvailable()`, `tools()`, `callTool(...)`, `deliverChannelMessage(channel, message)`, `close()` |
| `EntryPointResolver` | Entry-AuflÃ¶sung eines Bundles (`package.json` â†’ `main`, Traversal-Schutz, dann `src/index.js` â€¦ `main.js`) |

Testabdeckung (P1-03): `JsonRpcLineCodecTest` (Codec/Framing) und `NodeSidecarBridgeTest` (Integration mit echtem Node.js; Ã¼bersprungen, wenn Node nicht verfÃ¼gbar). Abgedeckt: Handshake, ping/info/listTools, Tool-Aufruf (Erfolg + Fehler), Method-NotFound, Call-Timeout, Restart (neue PID), Close, Aufruf nach Close/Restart-nach-Close.

Starten: `NodeSidecarBridge.start(ObjectMapper)` startet das Referenz-Sidecar (`protocol-sidecar.js`); `NodeSidecarBridge.pluginScript()` liefert das Plugin-Runtime-Sidecar (`plugin-sidecar.js`). Das Script wird in eine **temporÃ¤re Datei** geschrieben und als `<file>`-Argument gestartet â€” `-e`-Argumente unterliegen auf Windows der `CreateProcess`-LÃ¤ngenbegrenzung (~8191 Zeichen) und grÃ¶ÃŸere Scripts starteten sonst nicht (behoben mit P4-01).

## 8. Plugin-Laufzeit (P4-01)

Das Plugin-Runtime-Sidecar (`sidecar/plugin-sidecar.js`) stellt die OpenClaw-Entry-Semantik bereit: Plugins registrieren ihre **Tools, Commands und Hooks zur Laufzeit** (statt statisch) Ã¼ber `definePluginEntry` (Agent-Plugins) bzw. `defineChannelPluginEntry` (Channel-Plugins).

### Entry-Vertrag (Referenz-Laufzeit)

Die Plugin-Source ist ein **CommonJS-Entry** ohne npm/TypeScript-AbhÃ¤ngigkeiten; die Kontrakte `definePluginEntry`/`defineChannelPluginEntry` stellt das Sidecar als Globals in einer `vm`-Sandbox bereit:

```js
module.exports = definePluginEntry({
  id: 'acme/demo',
  name: 'Demo',
  register(api) {
    api.registerTool({
      name: 'greet',
      description: 'BegrÃ¼ÃŸt jemanden.',
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

`register(api)` erhÃ¤lt: `registerTool({name, description, parameters, outputSchema, execute})`, `registerCommand({name, description, execute})` und `on(event, handler, {matcher, priority})`. Hooks werden in absteigender `priority` ausgefÃ¼hrt; `matcher` ist ein Name-String, RegExp oder `undefined` (alle Tools). `before_tool_call`/`after_tool_call` werden beim `tool.call` im Sidecar ausgefÃ¼hrt â€” ein werfender Handler blockiert den Aufruf bzw. das Ergebnis mit **`ERROR_HOOK_BLOCKED` (-32005)**.

### Methoden

| Methode | Params | Ergebnis |
|---|---|---|
| `plugin.load` | `{id, source}` | Quittung `{id, name, tools:[{name, description, parameters}], commands:[String], channels:[{name, description}], hooks:[{event, priority}]}`; ESM-transpilierte Sources (`exports.default`) werden aufgelÃ¶st; `plugin` in vm-Sandbox ohne Zugriff auf `require`/`process` |
| `plugin.unload` | `{id}` | `{id, removed}` â€” entfernt Tools/Commands/Channels/Hooks des Plugins (statische Referenz-Tools werden wiederhergestellt) |
| `channel.deliver` | `{channel, message}` | Stellt eine eingehende Nachricht (`message`) an den `receive`-Handler des registrierten Channels zu (Empfang, P4-01): Ergebnis = Handler-Ergebnis; Fehler: `ERROR_CHANNEL_NOT_FOUND` (-32007, unbekannter Channel), `ERROR_CHANNEL_EXECUTION` (-32008, `receive()` warf) |

`sidecar.listTools` liefert Referenz- + Plugin-Tools (Plugin-Tools mit `pluginId`). `tool.call` dispatched Ã¼ber die kombinierte Registry. Fehler: `ERROR_HOOK_BLOCKED` (-32005), `ERROR_PLUGIN_INVALID` (-32006, z. B. fehlende `definePluginEntry`/`register` oder Fehler in `register()`), `ERROR_CHANNEL_NOT_FOUND` (-32007), `ERROR_CHANNEL_EXECUTION` (-32008).

### Entry-AuflÃ¶sung (Java)

`EntryPointResolver` lÃ¶st den Entry-Punkt eines Bundles auf: `package.json` â†’ `main` (nur innerhalb des Plugin-Ordners, **Traversal-Schutz**), sonst Fallbacks `src/index.js`, `src/index.mjs`, `index.js`, `index.mjs`, `main.js`. Ohne Entry bleibt das Plugin Control-Plane-only (`load()` liefert `Optional.empty()`).

### Laufzeit-Wiring

`NodeSidecarPluginRuntime` (`@Component`, aktiv bei `jclaw.agent.plugins.runtime.enabled=true`, Deny-by-Default) startet den Node-Sidecar lazy, lÃ¤dt Ã¼ber `load()`/`loadAvailable()` alle gÃ¼ltigen OpenClaw-Plugins mit Entry-Point und hÃ¤lt die Load-Quittungen. Tools sind Ã¼ber `tools()`/`callTool()` erreichbar; `close()` beendet den Sidecar-Prozess.

## 9. Verbleibende offene Punkte (nach P4-01)

- **Tool-Schema â†’ Spring-AI:** `parameters` (JSON-Schema) bislang roher Knoten (`SidecarToolDescriptor.parameters()`); Anbindung an das Spring-AI-Tool-Calling (`@Tool`, `JsonSchema`) **umgesetzt (hermetisches Binding, P4-01):** `PluginToolCallback` (Spring-AI-1.0-{@link ToolCallback}, record `SidecarToolDescriptor` + `BiFunction<String,String,String>`-Dispatcher) exponiert `getToolDefinition()` (DefaultToolDefinition; `inputSchema` aus `descriptor.parameters()` mit Fallback `{"type":"object","properties":{}}` bei `null`) und dispatched `call(String)`; **Umgesetzt (P4-01, hermetisch):** der Spring-AI-Merge in den Agent-Callback-Listen (mirroring `McpToolRegistry`) ist grün, PluginToolCallbacks aus `NodeSidecarPluginRuntime.toolCallbacks()` flieÃŸen (Spiegel-Test 743) &mdash; siehe Abschnitt 8 + ADR.
- **npm/TypeScript-Bundles:** `definePluginEntry`-Shim + CommonJS sind die **Referenz-Laufzeit**; echte OpenClaw-Bundles (ESM-TypeScript, `openclaw/plugin-sdk`-Imports) benÃ¶tigen npm-AuflÃ¶sung/Bundling â€” gegen den neuen SDK-Stand (Subpath-Imports, moderne Hook-Stages, `setup`-Deskriptoren).
- **Hooks/Channels:** **Voll-Hook-Katalog (P1-11) umgesetzt (hermetisches Binding, Spiegel-Test 744 grün):** `HOOK_EVENTS` enthält alle modernen SDK-Stages (`openclaw-compat.md` §3: Agent-Turn, Tools inkl. `tool_result_persist`, Messages, Sessions, Lifecycle inkl. `cron_reconciled`/`cron_changed`, Installs, Skills; `before_agent_start`/SDK-Root-Imports bleiben seit 2026.6.34 entfernt und werden abgelehnt); die `before_tool_call`/`after_tool_call`-Hooks laufen weiter aus. **Channel-Runtime (P4-01) umgesetzt (hermetisches Binding, Spiegel-Test 746 grün):** `defineChannelPluginEntry`-Plugins registrieren Empfangs-Channels über `api.registerChannel({name, description, receive})` (Quittung `{name, description}`, unload entfernt sie); der Java-Kern stellt eingehende Nachrichten (analog `ChannelMessage.inbound`) über dieselbe Bridge als `channel.deliver` an den `receive`-Handler zu (Fehler: `ERROR_CHANNEL_NOT_FOUND` -32007, `ERROR_CHANNEL_EXECUTION` -32008).
- **Backpressure/Parallelität:** **umgesetzt (hermetisches Binding, Spiegel-Test 749 grün):** die Bridge bleibt id-basiert (Eine-Antwort-pro-Request), begrenzt aber gleichzeitige In-Flight-Requests je Sidecar über einen fairen Semaphore auf `NodeSidecarBridge.DEFAULT_MAX_CONCURRENT_REQUESTS` (**8**, Default `maxConcurrentRequests`); ein Aufruf, dessen Slot nicht innerhalb von `DEFAULT_BACKPRESSURE_WAIT_MILLIS` (**5 s**) frei wird, scheitert mit `ERROR_BUSY` (-32009 `SidecarCallException`) statt unbegrenzt zu warten; stdio-Schreibzugriffe sind serialisiert (parallele Aufrufer teilen sich dieselbe stdin-Pipe). Konfigurierbar über `NodeSidecarBridge.start(...)`; Tests: Burst bis zum Limit (Spiegel-Test 748) und Ablehnung darüber (Spiegel-Test 749).
- **Stderr-Auswertung:** **umgesetzt (hermetisches Binding, Spiegel-Test 750/751 grün):** stderr wird weiterhin gedrained (nie als Protokoll geparst), aber pro Prozesslauf gesammelt (max. 20 Zeilen). Scheitert der Start (fehlendes npm-Modul, Syntaxfehler: Ready-Timeout) bzw. crasht der Prozess, fließen die stderr-Zeilen in die Fehlermeldung ein (`SidecarTimeoutException` bzw. `IOException` der Bridge). stderr-Ausgaben während des normalen Betriebs stören das Protokoll weiterhin nicht (Spiegel-Test 751).
