# JClaw → OpenClaw-Parität: Roadmap

Stand: 2026-08-15 · Basis: `docs/openclaw-compat.md` (Formatanalyse) · Ziel: **100 % Parität** zu OpenClaw.

## Status-Legende

| Symbol | Bedeutung |
|---|---|
| ✅ | Erledigt (inkl. Tests) |
| 🔵 | In Arbeit |
| ⬜ | Geplant / nicht begonnen |
| 🚫 | Entscheidung ausstehend (Architektur) |

Prioritäten: 🔴 hoch · 🟡 mittel · 🟢 niedrig

---

## Phase 0 — Fundament (✅ abgeschlossen)

Hexagonale Basis, damit alle weiteren Bausteine aufsetzen können.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P0-01 | Skill-Loader | `SKILL.md`-Format (YAML-Frontmatter) aus `jclaw.agent.skills.dir` laden, sortieren, überspringen ungültiger Ordner | 🔴 | ✅ |
| P0-02 | Skill-Injektion | Aktivierte Skills per `jclaw.agent.skills.enabled` (Deny-by-Default) in den System-Prompt injizieren | 🔴 | ✅ |
| P0-03 | Kern-Tool: Rechner | `calculate` mit eigenem sicheren Ausdrucks-Parser | 🔴 | ✅ |
| P0-04 | Kern-Tool: Datum/Zeit | `getCurrentDateTime` mit optionaler Zeitzone | 🟡 | ✅ |
| P0-05 | Kern-Tool: Datei | `readFile`, `listDirectory`, `writeFile` mit Workdir-Scoping + Traversal-Schutz | 🔴 | ✅ |
| P0-06 | Kern-Tool: Datei-Suche | `glob` (Glob-Muster) und `grep` (Regex) innerhalb des Workdirs | 🔴 | ✅ |
| P0-07 | Kern-Tool: Shell | `runCommand` mit Workdir, Timeout, Output-Limit (opt-in, Deny-by-Default) | 🟡 | ✅ |
| P0-08 | Konversations-Memory | Message-Window je `contextId`, Speicherung/Abfrage/Löschung über REST | 🔴 | ✅ |
| P0-09 | Persistenz | H2-JDBC-`ChatMemory` (`chat_message`-Tabelle, überlebt Neustarts) | 🔴 | ✅ |
| P0-10 | Skill-/Konversations-API | `GET /api/v1/skills`, `GET|DELETE /api/v1/conversations/{contextId}` | 🟡 | ✅ |
| P0-11 | Agent-Loop | Tool-Calling-Loop mit Iterationslimit + `AgentLoopLimitExceededException` | 🔴 | ✅ |
| P0-12 | Fehlerbehandlung | Globaler `@RestControllerAdvice` (400/500) | 🟡 | ✅ |

---

## Phase 1 — Kern-Parität (aktuell)

Agent-Kern-Fähigkeiten, die OpenClaw zusätzlich bietet und die ohne JS möglich sind.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P1-01 | Plugin Control-Plane | Manifeste lesen/validieren (`openclaw.plugin.json` + Agent-Plugins/Codex/Claude/Cursor), ohne Codeausführung; `GET /api/v1/plugins` | 🔴 | ✅ |
| P1-02 | Architektur-Entscheidung | **Node-Sidecar bestätigt** (JSON-RPC 2.0 über stdio). Spike validiert Java ↔ Node-Kommunikation. Siehe [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md) | 🔴 | ✅ |
| P1-03 | Bridge-Protokoll | Vollständige JSON-RPC-Spezifikation (Framing, Methoden-Katalog, Fehlercodes, Timeouts, Restart) — [bridge-protocol.md](bridge-protocol.md); Bridge als verwaltbarer Dienst (Handshake, Call-/Ready-Timeout, `restart()`) | 🔴 | ✅ |
| P1-04 | MCP-Client | `mcp.servers`-Unterstützung: externe Model Context Protocol-Server als Tools integrieren (`jclaw.mcp.servers.*`, HTTP + STDIO, Deny-by-Default) | 🔴 | ✅ |
| P1-05 | Web-Tools | `web_fetch` (mit `allowedDomains`-Policy) und `web_search` | 🟡 | ✅ |
| P1-06 | Kern-Tool: Patch | `apply_patch` für strukturierte Datei-Änderungen | 🟡 | ✅ |
| P1-07 | Kern-Tool: Agent | `spawn_agent` / Multi-Agent-Subprozesse | 🟢 | ⬜ |
| P1-08 | Tool-Policies | Allow-/Denylisten je Agent (`tools.allow`), `toolMetadata.autoApproved` | 🟡 | ⬜ |
| P1-09 | Session-Konzept | Von `contextId` auf Sessions erweitern (Reset-Strategien `daily`/`idle`, Thread-Bindings, `dmScope`) | 🟡 | ⬜ |
| P1-10 | Compaction | Kontext-Kompression bei Session-Grenzen | 🟢 | ⬜ |
| P1-11 | Hooks | `HOOK.md`-Scripts + Lifecycle-Events (before_tool_call, before_agent_run, …) via Script-Runner | 🟡 | ⬜ |
| P1-12 | Cron-Jobs | Wiederkehrende Agent-Jobs (`cron.*`-Konfiguration) | 🟢 | ⬜ |

---

## Phase 2 — Konfiguration & Gateway

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P2-01 | JSON5-Konfig | `openclaw.json`-Format (Kommentare, Trailing Commas), `$include`, `${VAR}`-Substitution | 🔴 | ⬜ |
| P2-02 | Schema-Validierung | Strikte Validierung; Gateway startet bei ungültiger Konfiguration nicht | 🔴 | ⬜ |
| P2-03 | Hot-Reload | Auto-Detect + manuelles `config.apply`; laufende Agents behalten ihre Config | 🟡 | ⬜ |
| P2-04 | Gateway-Steuerung | Lokaler Kontrollserver: Sessions, Plugins, Hooks, Cron verwalten | 🟡 | ⬜ |
| P2-05 | Control-UI | Web-Oberfläche (statische SPA, kein Build-Schritt) über die REST-API: Agent, Konversationen, Skills, Plugins | 🟢 | ✅ |
| P2-06 | Auth | Gateway-Authentifizierung (API-Token) | 🟡 | ⬜ |

---

## Phase 3 — Channels (100 %-Parität)

OpenClaw-Kernfeature: Nachrichten von/nach externen Plattformen.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P3-01 | Channel-API | Abstraktion (send/receive) + Session-Bindung (DM-/Thread-Bindung) | 🔴 | ⬜ |
| P3-02 | Telegram | Channel-Adapter (Polling/Webhook) | 🟡 | ⬜ |
| P3-03 | Slack | Channel-Adapter (Socket Mode) | 🟡 | ⬜ |
| P3-04 | Discord | Channel-Adapter (WebSocket) | 🟡 | ⬜ |
| P3-05 | WhatsApp | Channel-Adapter | 🟡 | ⬜ |
| P3-06 | X / Signal / E-Mail | Weitere OpenClaw-Channel | 🟢 | ⬜ |

---

## Phase 4 — Fortgeschrittene Parität

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P4-01 | Plugin-Laufzeit | Node-Sidecar führt `definePluginEntry`/`defineChannelPluginEntry` aus (setzt P1-03 voraus) | 🔴 | 🚫 |
| P4-02 | Wissen-Memory | Semantisches Memory über Embeddings (z. B. pgvector/Chroma), `kind: "memory"`, Injektion relevanter Chunks | 🟡 | ⬜ |
| P4-03 | Media-Provider | Speech/Media-Provider (TTS/STT) | 🟢 | ⬜ |
| P4-04 | Provider-Abstraktion | Modell-Provider über Ollama hinaus (OpenAI-kompatibel, Anthropic, …) via Spring AI | 🟡 | ⬜ |
| P4-05 | Paritäts-Testsuite | Automatisierte Konformitäts-Checks: Manifeste, Konfig, Hooks, Tool-Schemas gegen OpenClaw-Referenz | 🟡 | ⬜ |

---

## Release-Plan

Die Bausteine werden nicht einzeln, sondern in Versionen mit einem in sich geschlossenen, testbaren Ergebnis gebündelt. Die Reihenfolge folgt den Prioritäten (🔴 zuerst) und den Abhängigkeiten. Bis zur vollen Parität gilt SemVer (`0.x`); `1.0.0` = 100 % Parität.

| Version | Thema | Bausteine | Ziel / Wert |
|---|---|---|---|
| **0.1.0** | Agent-Kern | ~~P1-06~~ ✅ `apply_patch`, P1-08 Tool-Policies, P1-09 Session-Konzept | Verlässlicher Einzel-Agent mit Policy- und Session-Modell |
| **0.2.0** | Konfiguration & Gateway | P2-01 JSON5-Konfig, P2-02 Schema-Validierung, P2-03 Hot-Reload, P2-04 Gateway-Steuerung, P2-06 Auth | Steuerungsebene als Anker für Hooks, Cron und Channels |
| **0.3.0** | Multi-Agent & Plugins | P1-07 `spawn_agent`, P4-01 Plugin-Laufzeit, P4-04 Provider-Abstraktion, P1-12 Cron | OpenClaw-Parität beim Agent-Verhalten |
| **0.4.0** | Channels | P3-01 Channel-API, P3-02 Telegram, P3-03 Slack, P3-04 Discord, ~~P2-05~~ ✅ Control-UI | Nutzbares Multi-Plattform-Produkt |
| **1.0.0** | 100 % Parität | P1-11 Hooks, P1-10 Compaction, P4-02 Memory, P4-03 Media, P4-05 Paritäts-Testsuite, restliche Channels | Feature-Parität, erste stabile Version |

Anmerkungen:

- `0.1.0-SNAPSHOT` ist die aktuelle Entwicklungsversion (siehe `pom.xml`); abgeschlossene Versionen werden als Release getaggt.
- Abhängigkeiten: P1-12 (Cron) setzt das Session-Konzept (P1-09) und das Gateway voraus, P4-01 setzt die Bridge (P1-03) voraus, Channels setzen die Gateway-Steuerung (P2-04) voraus.

## Offene Architektur-Entscheidungen

> **MCP-Integration (P1-04) getroffen:** Nutzung des **Spring-AI-Ökosystems** (`spring-ai-starter-mcp-client` + `io.modelcontextprotocol.sdk`). Eigene `McpToolRegistry` mit `jclaw.mcp.servers.*`-Konfiguration (HTTP/STDIO, Deny-by-Default).

> **Web-Tools (P1-05) getroffen:** `web_fetch` mit `allowedDomains`-Policy (Subdomains erlaubt, nur http/https, Größenlimit) und `web_search` über einen konfigurierbaren Such-Endpoint (Standard: DuckDuckGo Instant Answer, `jclaw.agent.webtool.search-endpoint`). Beide sind Deny-by-Default (`jclaw.agent.webtool.enabled`).

> **Control-UI (P2-05) getroffen:** Bewusst **keine Framework-SPA** (kein Build-Schritt, keine externen CDN-Abhängigkeiten). Eine statische SPA in `src/main/resources/static/` (Vanilla-JS + `fetch`) bindet die vorhandene REST-API an (`POST /api/v1/tasks`, `GET /api/v1/skills`, `GET|DELETE /api/v1/conversations/{contextId}`, `GET /api/v1/plugins`). Die Gateway-/Session-Steuerung aus der ursprünglichen P2-05-Beschreibung bleibt Teil von P2-04.

> Die Plugin-Laufzeit-Entscheidung (Node-Sidecar vs. GraalJS vs. Java-Reimplementation) ist getroffen: **Node-Sidecar**, siehe [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md). Das Bridge-Protokoll ist vollständig spezifiziert (siehe [bridge-protocol.md](bridge-protocol.md), P1-03).

## Definition of Done (Paritäts-Kriterien)

Ein Baustein gilt als paritätisch, wenn:

- das OpenClaw-Format **1:1** verstanden wird (Frontmatter, Manifest, Konfig-Schema),
- die Verhaltenssemantik übernommen ist (Deny-by-Default, Allowlisten, Timeouts, Retry/Fehlerverhalten),
- die Sicherheits-Policy mindestens OpenClaw-Niveau hat,
- der Baustein per REST/Test automatisierbar und mit Tests abgedeckt ist,
- die Doku (README bzw. dieses Dokument) den Status spiegelt.

## Nächste Schritte

1. **P1-08** Tool-Policies (Allow-/Denylisten je Agent, `toolMetadata.autoApproved`).
2. **P1-09** Session-Konzept auf der H2-Persistenz aufbauen.
