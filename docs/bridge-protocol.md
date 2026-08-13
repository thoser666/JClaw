# Bridge-Protokoll: Java-Kern ↔ Node-Sidecar

- Status: **Festgelegt (P1-03)**
- Datum: 2026-08-13
- Bezug: [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md) · [Paritäts-Roadmap](parity-roadmap.md) P1-03
- Implementierung: `biz.brumm.infrastructure.sidecar.*` · Referenz-Sidecar: `src/main/resources/sidecar/protocol-sidecar.js`

## 1. Überblick

Der Java-Kern kommuniziert mit einem externen **Node.js-Sidecar-Prozess** über **JSON-RPC 2.0**, Newline-delimited über **stdio**. Der Sidecar ist die spätere Plugin-Laufzeit (P4-01: `definePluginEntry`/`defineChannelPluginEntry`); die Bridge selbst bleibt generisch, damit sie auch für Hooks oder MCP-Skripte nutzbar bleibt.

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
| `NodeSidecarBridge` | Verwaltete Bridge: `start(...)`, `ping()`, `info()`, `listTools()`, `callTool(name, args)`, `restart()`, `close()` |
| `JsonRpcMessage` | Nachrichtenmodell (Request/Response/Error/Notification, strukturierte Fehler) |
| `JsonRpcLineCodec` | NDJSON-Encoding/-Decoding (reines Framing, unit-getestet) |
| `SidecarCallException` | Sidecar-Fehler mit JSON-RPC-Fehlercode |
| `SidecarTimeoutException` | Call-/Ready-Timeout |
| `SidecarToolDescriptor` | Tool-Registrierung (`name`, `description`, `parameters`) |

Testabdeckung (P1-03): `JsonRpcLineCodecTest` (Codec/Framing) und `NodeSidecarBridgeTest` (Integration mit echtem Node.js; übersprungen, wenn Node nicht verfügbar). Abgedeckt: Handshake, ping/info/listTools, Tool-Aufruf (Erfolg + Fehler), Method-NotFound, Call-Timeout, Restart (neue PID), Close, Aufruf nach Close/Restart-nach-Close.

## 8. Offene Punkte für P4-01

- **Plugin-Laufzeit:** Sidecar führt `definePluginEntry`/`defineChannelPluginEntry` aus; Tools/Commands/Hooks werden zur Laufzeit registriert statt statisch (`sidecar.listTools` liefert dann Plugin-Tools).
- **Tool-Schema:** `parameters` an das Spring-AI-Tool-Calling (`@Tool`, `JsonSchema`-Annotationen) anbinden.
- **Backpressure/Parallelität:** Bisher eine Antwort pro Request (id-basiert); keine Limits für gleichzeitige Aufrufe definiert.
- **Hooks/Channels:** Lifecycle-Events (P1-11) und Channel-Bridge (P3-01) laufen über dieselbe Bridge; Methoden-Katalog wird erweitert.
- **Stderr-Auswertung:** Bisher nur Log; bei Startfehlern (fehlendes npm-Modul) könnte stderr gezielt in die Fehlermeldung fließen.
