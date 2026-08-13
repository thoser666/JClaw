# ADR 0001: Plugin-Laufzeit über Node-Sidecar

- Status: **Akzeptiert**
- Datum: 2026-08-12
- Bezug: [docs/openclaw-compat.md](../openclaw-compat.md) · [docs/parity-roadmap.md](../parity-roadmap.md) P1-02

## Kontext

Für **100 %-Parität** mit OpenClaw muss JClaw dessen Plugins ausführen können. OpenClaw-Plugins sind **TypeScript/npm-Pakete**, die zur Laufzeit die `openclaw`-Runtime importieren und sich über `definePluginEntry` / `defineChannelPluginEntry` registrieren (Tools, Commands, Hooks, Channels, Provider). Das ist nur über eine JavaScript-Laufzeit ausführbar — eine reine Java-Reimplementation kann ausschließlich den OpenClaw-Kern (nicht Third-Party-Plugins) nachbilden.

Zu entscheiden war, **wie** die Plugin-Laufzeit in JClaw eingebettet wird.

## Entscheidungstreiber

| Treiber | Gewicht |
|---|---|
| 1:1-Plugin-Kompatibilität (echte npm/TypeScript-Bundles ausführen) | 🔴 hoch |
| Isolation/Stabilität (Plugin-Crash darf den Agent-Kern nicht reißen) | 🔴 hoch |
| Realisierbarer Aufwand + Wartbarkeit in einem Java-Projekt | 🟡 mittel |
| Keine Abhängigkeit vom Build-Prozess der Anwendung (kein npm-Bundle in die JAR) | 🟡 mittel |

## Alternativen

### 1. Node-Sidecar (gewählt)

Externer Node.js-Prozess neben dem Java-Kern; Kommunikation über **JSON-RPC 2.0 über stdio** (Newline-delimited). Java ruft Plugins über die Bridge auf; Ergebnisse kommen als JSON zurück.

- **+** Echte 1:1-Kompatibilität (beliebige npm-/TypeScript-Plugins, exakt wie OpenClaw).
- **+** Vollständige Isolation (Absturz/Hang des Sidecars wirkt sich nicht auf den Kern aus).
- **+** Kein Einfluss auf den Java-Build; keine JS-Engine im JVM-Prozess.
- **+** Lebenszyklus steuerbar (Start/Stop/Restart, Timeouts, Ressourcenbegrenzung).
- **−** Zusätzliche Laufzeit-Anforderung: Node.js auf Zielsystemen (dokumentieren, wie OpenClaw es tut).
- **−** IPC-Overhead und Prozess-Management (akzeptabel für Tool-/Hook-Aufrufe).

### 2. GraalJS (eingebettet)

JS-Engine im JVM-Prozess (Polyglot).

- **+** Kein externer Prozess, kein IPC.
- **−** npm/ESM-Auflösung und Node-API-Kompatibilität (net, fs, readline …) im eingebetteten Kontext sind komplex und unvollständig; Third-Party-Plugins, die Node-Core-Module nutzen, laufen oft nicht.
- **−** Hoher Portierungs-/Pflegeaufwand; Crashes im selben Prozess.
- **−** GraalVM-Runtime-Voraussetzung.

### 3. Java-Reimplementation

OpenClaw-Kernlogik direkt in Java.

- **+** Kein JS nötig, volle Java-Kontrolle.
- **−** **Third-Party-Plugins sind unmöglich** (sie sind TypeScript und importieren die `openclaw`-Runtime) → 100 %-Parität nicht erreichbar.
- **−** Nachentwicklung des gesamten Plugin-Ökosystems.

## Entscheidung

**Node-Sidecar** mit JSON-RPC 2.0 (Newline-delimited) über stdio. Der Java-Kern übernimmt Control-Plane (Manifeste lesen/validieren, Konfig), der Sidecar die Plugin-Laufzeit (Tool-/Hook-/Command-Registrierung, Channels). Diese Struktur entspricht der in `docs/openclaw-compat.md` §8 empfohlenen Architektur.

Die Bridge wird als eigenständige, schwach gekoppelte Komponente aufgebaut (siehe P1-03), damit sie auch für andere JS-basierte Erweiterungen (Hooks, MCP-Skripte) nutzbar bleibt.

## Konsequenzen

- **Positiv:** 1:1-Plugin-Parität, Isolation, Wartbarkeit, klare Schnittstelle (JSON-RPC).
- **Negativ:** Node.js-Runtime muss dokumentiert und vorausgesetzt werden; Prozess-Lebenszyklus- und Fehlerbehandlung (Restart, Timeouts, Backpressure) müssen im Bridge-Code geführt werden.

## Validierung (Feasibility-Spike → P1-03)

Die Entscheidung ist durch einen lauffähigen Spike validiert und in **P1-03** zum vollständigen Bridge-Protokoll ausgebaut:

- `biz.brumm.infrastructure.sidecar.JsonRpcMessage` / `JsonRpcLineCodec` — JSON-RPC-2.0-Framing (NDJSON, strukturierte Fehler, Notifications; pure Java, unit-getestet).
- `biz.brumm.infrastructure.sidecar.NodeSidecarBridge` — verwalteter Dienst: startet `node -e <script>`, Handshake über `sidecar.ready`, async Reader-Thread mit id-basiertem Dispatch, Call-/Ready-Timeout (`SidecarTimeoutException`), strukturierte Fehler (`SidecarCallException`), `restart()`/`close()` mit Graceful-Shutdown.
- Protokoll-Spezifikation: [docs/bridge-protocol.md](../bridge-protocol.md).
- Tests: `JsonRpcLineCodecTest` (Codec) und `NodeSidecarBridgeTest` (Integration mit echtem Node.js; übersprungen, wenn Node nicht verfügbar).

Damit ist die zentrale Risikofrage (Java ↔ Node über JSON-RPC/stdio funktioniert) beantwortet; P4-01 baut die eigentliche Plugin-Laufzeit auf der Bridge auf.
