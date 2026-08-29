# JClaw → OpenClaw-Parität: Roadmap

Stand: 2026-08-25 · Basis: `docs/openclaw-compat.md` (Formatanalyse) · Referenz-Version: OpenClaw **2026.7.1** (Stable) / **2026.8.1-beta.2** (Betainhalt) · Ziel: **100 % Parität** zu OpenClaw.

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

## Phase 1 — Kern-Parität (abgeschlossen ✅)

Agent-Kern-Fähigkeiten, die OpenClaw zusätzlich bietet und die ohne JS möglich sind. Alle Bausteine (P1-01 bis P1-12) sind implementiert.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P1-01 | Plugin Control-Plane | Manifeste lesen/validieren (`openclaw.plugin.json` + Agent-Plugins/Codex/Claude/Cursor), ohne Codeausführung; `GET /api/v1/plugins` | 🔴 | ✅ |
| P1-02 | Architektur-Entscheidung | **Node-Sidecar bestätigt** (JSON-RPC 2.0 über stdio). Spike validiert Java ↔ Node-Kommunikation. Siehe [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md) | 🔴 | ✅ |
| P1-03 | Bridge-Protokoll | Vollständige JSON-RPC-Spezifikation (Framing, Methoden-Katalog, Fehlercodes, Timeouts, Restart) — [bridge-protocol.md](bridge-protocol.md); Bridge als verwaltbarer Dienst (Handshake, Call-/Ready-Timeout, `restart()`) | 🔴 | ✅ |
| P1-04 | MCP-Client | `mcp.servers`-Unterstützung: externe Model Context Protocol-Server als Tools integrieren (`jclaw.mcp.servers.*`, HTTP + STDIO, Deny-by-Default) | 🔴 | ✅ |
| P1-05 | Web-Tools | `web_fetch` (mit `allowedDomains`-Policy) und `web_search` | 🟡 | ✅ |
| P1-06 | Kern-Tool: Patch | `apply_patch` für strukturierte Datei-Änderungen | 🟡 | ✅ |
| P1-07 | Kern-Tool: Agent | `spawn_agent` / Multi-Agent-Subprozesse (Deny-by-Default, max-depth-Limit) | 🟢 | ✅ |
| P1-08 | Tool-Policies | Allow-/Denyliste je Agent via `jclaw.agent.tools.allow`/`.deny` (Deny-by-Default, Deny schlägt Allow; deaktivierte Tools erscheinen nicht im Tool-Schema) | 🟡 | ✅ |
| P1-09 | Session-Konzept | Von `contextId` auf Sessions erweitern (Reset-Strategien `daily`/`idle`, generierte Titel, REST-API); Session-first-UI seit 2026.7.1 als Referenz. Scope-Abgrenzung: Thread-Bindings, dmScope, Session-Gruppen, Transcript-Export und Kontext-Verbrauch sind P2-04 (Gateway) | 🟡 | ✅ |
| P1-10 | Compaction | Kontext-Kompression bei Session-Grenzen | 🟢 | ✅ |
| P1-11 | Hooks | `HOOK.md`-Scripts + Lifecycle-Events (before_tool_call, before_agent_run, …) via Script-Runner; Hook-Stage-Migration beachten (`before_agent_start`/SDK-Root-Imports seit 2026.6.34 entfernt) | 🟡 | ✅ |
| P1-12 | Cron-Jobs | Wiederkehrende Agent-Jobs (`cron.*`-Konfiguration) | 🟢 | ✅ |

---

## Phase 2 — Konfiguration & Gateway

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P2-01 | JSON5-Konfig | `openclaw.json`-Format (Kommentare, Trailing Commas), `$include`, `${VAR}`-Substitution | 🔴 | ✅ |
| P2-02 | Schema-Validierung | Strikte Validierung; Gateway startet bei ungültiger Konfiguration nicht | 🔴 | ✅ |
| P2-03 | Hot-Reload | Auto-Detect + manuelles `config.apply`; laufende Agents behalten ihre Config | 🟡 | ✅ |
| P2-04 | Gateway-Steuerung | Session-Gruppen, Transcript-Export, Gateway-Status- & Info-API | 🟡 | ✅ |
| P2-05 | Control-UI | Web-Oberfläche (statische SPA, kein Build-Schritt) über die REST-API: Agent, Konversationen, Skills, Plugins | 🟢 | ✅ |
| P2-06 | Auth | Gateway-Authentifizierung (API-Token, Bearer-Header, SHA-256-Hash, Deny-by-Default) | 🟡 | ✅ |
| P2-09 | Color-Schemes | Light/Dark-Theme via CSS-Variablen (`prefers-color-scheme` + manueller Toggle), Designsystem für die Control-UI | 🟡 | ✅ |

---

## Phase 3 — Channels (100 %-Parität)

OpenClaw-Kernfeature: Nachrichten von/nach externen Plattformen.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P3-01 | Channel-API | Abstraktion (send/receive) + Session-Bindung (DM-/Thread-Bindung) | 🔴 | ✅ |
| P3-02 | Telegram | Channel-Adapter (Polling/Webhook) | 🟡 | ✅ |
| P3-03 | Slack | Channel-Adapter (Socket Mode) | 🟡 | ✅ |
| P3-04 | Discord | Channel-Adapter (WebSocket) | 🟡 | ⬜ |
| P3-05 | WhatsApp | Channel-Adapter | 🟡 | ⬜ |
| P3-06 | Weitere Channels | Buzz (Nostr), ClickClack, IRC, Google Chat, Synology Chat, Mattermost, Feishu u. a. | 🟢 | ⬜ |

---

## Phase 4 — Fortgeschrittene Parität

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P4-01 | Plugin-Laufzeit | Node-Sidecar führt `definePluginEntry`/`defineChannelPluginEntry` aus (setzt P1-03 voraus) | 🔴 | 🚫 |
| P4-02 | Wissen-Memory | Semantisches Memory über Embeddings (z. B. pgvector/Chroma), `kind: "memory"`, Injektion relevanter Chunks | 🟡 | ⬜ |
| P4-03 | Media-Provider | Speech/Media-Provider (TTS/STT) | 🟢 | ⬜ |
| P4-04 | Provider-Abstraktion | Modell-Provider über Ollama hinaus (OpenAI-kompatibel, Anthropic, …) via Spring AI; Referenz-Stand 2026.7.1: GPT-5.6 (+ Sol/Terra/Luna), Claude Sonnet 5, Meta Muse Spark 1.1, Featherless, ClawRouter, lokales Setup (Ollama/llama.cpp/LM Studio) | 🟡 | ⬜ |
| P4-05 | Paritäts-Testsuite | Automatisierte Konformitäts-Checks: Manifeste, Konfig, Hooks, Tool-Schemas gegen OpenClaw-Referenz | 🟡 | ⬜ |
| P4-06 | Skill Workshop | Vorschlags-Verwaltung (`skills.workshop.*`): Proposals, apply/reject/quarantine, `approvalPolicy: "pending"` (seit 2026.6.1) | 🟢 | ⬜ |

---

## Release-Plan

Die Bausteine werden nicht einzeln, sondern in Versionen mit einem in sich geschlossenen, testbaren Ergebnis gebündelt. Die Reihenfolge folgt den Prioritäten (🔴 zuerst) und den Abhängigkeiten. Bis zur vollen Parität gilt SemVer (`0.x`); `1.0.0` = 100 % Parität.

| Version | Thema | Bausteine | Ziel / Wert |
|---|---|---|---|
| **0.1.0** | Agent-Kern | ~~P1-06~~ ✅ `apply_patch`, ~~P1-07~~ ✅ `spawn_agent`, ~~P1-08~~ ✅ Tool-Policies, ~~P1-09~~ ✅ Session-Konzept | Verlässlicher Einzel-Agent mit Policy-, Session- und Multi-Agent-Modell |
| **0.2.0** | Konfiguration & Gateway | ~~P2-01~~ ✅ JSON5-Konfig, ~~P2-02~~ ✅ Schema-Validierung, ~~P2-03~~ ✅ Hot-Reload, ~~P2-04~~ ✅ Gateway-Steuerung, ~~P2-06~~ ✅ Auth, ~~P2-09~~ ✅ Color-Schemes | Steuerungsebene als Anker für Hooks, Cron und Channels |
| **0.3.0** | Multi-Agent & Plugins | P1-07 `spawn_agent`, P4-01 Plugin-Laufzeit, P4-04 Provider-Abstraktion, P1-12 Cron | OpenClaw-Parität beim Agent-Verhalten |
| **0.4.0** | Channels | P3-01 Channel-API, P3-02 Telegram, P3-03 Slack, P3-04 Discord, ~~P2-05~~ ✅ Control-UI | Nutzbares Multi-Plattform-Produkt |
| **1.0.0** | 100 % Parität | ~~P1-11~~ ✅ Hooks, ~~P1-10~~ ✅ Compaction, P4-02 Memory, P4-03 Media, P4-05 Paritäts-Testsuite, P4-06 Skill Workshop, restliche Channels | Feature-Parität, erste stabile Version |

Anmerkungen:

- `0.1.0-SNAPSHOT` ist die aktuelle Entwicklungsversion (siehe `pom.xml`); abgeschlossene Versionen werden als Release getaggt.
- Abhängigkeiten: P1-12 (Cron) setzt das Session-Konzept (P1-09) und das Gateway voraus, P4-01 setzt die Bridge (P1-03) voraus, Channels setzen die Gateway-Steuerung (P2-04) voraus.

## Offene Architektur-Entscheidungen

> **MCP-Integration (P1-04) getroffen:** Nutzung des **Spring-AI-Ökosystems** (`spring-ai-starter-mcp-client` + `io.modelcontextprotocol.sdk`). Eigene `McpToolRegistry` mit `jclaw.mcp.servers.*`-Konfiguration (HTTP/STDIO, Deny-by-Default).

> **Web-Tools (P1-05) getroffen:** `web_fetch` mit `allowedDomains`-Policy (Subdomains erlaubt, nur http/https, Größenlimit) und `web_search` über einen konfigurierbaren Such-Endpoint (Standard: DuckDuckGo Instant Answer, `jclaw.agent.webtool.search-endpoint`). Beide sind Deny-by-Default (`jclaw.agent.webtool.enabled`).

> **Tool-Policies (P1-08) getroffen:** OpenClaws `tools.allow`-Semantik ist als `jclaw.agent.tools.allow`/`.deny` umgesetzt. Ein `ToolPolicy`-Port (`domain.port.out`) mit `DefaultToolPolicy` (Properties-gestützt) entscheidet zentral, welche Tools aktiv sind; `OllamaAiAdapter` filtert die Spring-AI-`ToolCallback`s (inkl. MCP-Tools) beim Bauen, sodass deaktivierte Werkzeuge nie im Tool-Schema des Modells auftauchen. `toolMetadata.autoApproved` ist mit Auth (P2-06) verfügbar — Token-geschützte Endpunkte können auto-approved Tools erlauben.

> **Session-Konzept (P1-09) getroffen:** Session-first-UI (2026.7.1) als Referenz. Umgesetzt mit `Session`-Record (sessionId, displayName, sessionStartedAt, lastInteractionAt, updatedAt), `SessionStore`-Port und `H2SessionStore`-Implementierung (`session`-Tabelle in H2). `SessionService` verwaltet Session-Metadaten, Reset-Logik (`daily`/`idle`/`none` via `jclaw.session.*`) und leitet `displayName` aus der ersten Nachricht ab (max. 60 Zeichen). `ClawAgentService` löst Sessions beim Task-Call auf (per `contextId`), erstellt neue Sessions wenn keine existiert und prüft beim Touch ob ein Reset nötig ist. `AgentResponse` enthält jetzt `sessionId`. REST-API: `GET /api/v1/sessions`, `GET|DELETE /api/v1/sessions/{sessionId}`. Scope-Abgrenzung: Thread-Bindings, `dmScope`, Session-Gruppen, generierte Titel, Transcript-Export und Kontext-Verbrauch — diese Features sind Scope von P2-04 (Gateway-Steuerung) und P3 (Channels).

> **Spawn-Agent (P1-07) getroffen:** `spawn_agent` als Spring-AI-`@Tool` (`SpawnAgentTool`) mit `@ConditionalOnProperty(prefix = "jclaw.agent.spawnagent", havingValue = "true")` — Deny-by-Default. Der Sub-Agent ruft `AiProviderPort.execute()` mit eigener System-Prompt auf und hat dieselben Tools. Rekursionstiefe wird über `ThreadLocal<Integer>` je HTTP-Thread getrackt und durch `jclaw.agent.spawnagent.max-depth` (Standard: 3) begrenzt. Bei Rekursionslimit oder Provider-Fehler wird eine Fehlermeldung zurückgegeben. Die Architektur vermeidet zirkuläre Bean-Abhängigkeiten durch Konstruktor-Injektion über `SpawnAgentConfiguration`.

> **JSON5-Konfiguration (P2-01/P2-02) getroffen:** OpenClaw-kompatible `openclaw.json`-Datei wird beim Start geladen und als PropertySource in die Spring-Environment integriert (`EnvironmentPostProcessor`). Die JSON5-Datei verwendet Kurzschlüssel (z. B. `agents.max-iterations`), die automatisch auf Spring-Boot-Property-Namen gemappt werden (z. B. `jclaw.agent.max-iterations`). Unterstützt werden: Kommentare (`//`, `/* */`), Trailing Commas, `$include` für Datei-Einbindung, `${VAR}`-Substitution (intern + Umgebungsvariablen). Strikte Schema-Validierung (`Json5ConfigValidator`) prüft bei Start Top-Level-Bereiche, Session-Reset-Modi, Agent-Iterationen und MCP-Konfiguration — bei Fehlern wird der Start verhindert. Referenz: `de.marhali:json5-java:3.0.0` (Apache-2.0, keine Runtime-Dependencies).

> **Color-Schemes (P2-09) getroffen:** Die Control-UI nutzt bereits CSS-Variablen (`:root` in `app.css`) — ein Dark-Theme kann als `@media (prefairs-color-scheme: dark)` oder als `.dark`-Klasse ergänzt werden. Umgesetzt wird P2-09 nach P2-04 (Gateway-Steuerung), sodass die UI vor dem Theme-Wechsel funktional stabil ist. Das Designsystem umfasst: Light-Theme (Standard), Dark-Theme ( Sidebar dunkel, Surface invertiert), manueller Toggle im Header, Persistenz der Präferenz in `localStorage`.

> **Hot-Reload (P2-03) getroffen:** `Json5ConfigWatcher` überwacht `openclaw.json` über `java.nio.file.WatchService` (plattformübergreifend). Bei Änderungen wird mit 500 ms Debounce ein `Json5ConfigReloadService`-Reload ausgelöst: Die Datei wird neu geladen, gegen das Schema validiert und die Spring-Environment-PropertySource wird aktualisiert (Ersetzen, kein Hinzufügen). Laufende Agents behalten ihre Konfiguration — nur neue Aufrufe verwenden die aktualisierten Werte. Manueller Reload via `POST /api/v1/config.apply` (REST-API). Feature ist deaktiviert per Default (`jclaw.config.hot-reload.enabled=false`). Konfigurationsverzeichnis wird über `jclaw.config.dir` oder Arbeitsverzeichnis ermittelt.

> **Auth (P2-06) getroffen:** Bearer-Token-Authentifizierung über `Authorization`-Header. API-Token werden als SHA-256-Hash in einer H2-`api_key`-Tabelle gespeichert (Token selbst wird nur einmalig bei Erstellung angezeigt). `AuthInterceptor` (Spring `HandlerInterceptor`) prüft bei `/api/**`-Anfragen den Token; konfigurierbare öffentliche Pfade (`jclaw.auth.public-paths`) für Health-Checks und Static Resources. Feature ist deaktiviert per Default (`jclaw.auth.enabled=false`). Token-Verwaltung über REST-API: `GET/POST /api/v1/auth/tokens`, `DELETE /api/v1/auth/tokens/{id}`. Auth-Endpoint ist immer ohne Token erreichbar (Self-Service).

> **Control-UI (P2-05) getroffen:** Bewusst **keine Framework-SPA** (kein Build-Schritt, keine externen CDN-Abhängigkeiten). Eine statische SPA in `src/main/resources/static/` (Vanilla-JS + `fetch`) bindet die vorhandene REST-API an.

> **Hooks (P1-11) getroffen:** `HOOK.md`-Scripts mit YAML-Frontmatter (`name`, `stage`, `priority`, `script`) im konfigurierten Hook-Verzeichnis. `FileHookProvider` liest und parsed die Hooks, `HookScriptExecutor` führt Scripts via `ProcessBuilder` aus (Umgebungsvariablen `JCLAW_HOOK_*`, Exit-Code 0 = proceed). `HookService` orchestriert die Ausführung (sequenziell, absteigend nach Priorität, Blockierung möglich). Integration auf zwei Ebenen: (1) **Agent-Level** — `HookableAiProviderPort` (Decorator) ruft `before_agent_run`/`after_agent_run` Hooks auf; (2) **Tool-Level** — `OllamaAiAdapter` ruft `before_tool_call`/`after_tool_call` Hooks über `HookCallback`-Interface auf. Gateway-Lifecycle (`gateway_start`/`gateway_stop`) über `HookLifecycleListener` (Spring Events). Feature ist deaktiviert per Default (`jclaw.hooks.enabled=false`). Supported Stages: `gateway_start`, `gateway_stop`, `before_agent_run`, `after_agent_run`, `before_tool_call`, `after_tool_call`.

> **Compaction (P1-10) getroffen:** LLM-basierte Kontext-Kompression: Wenn die Nachrichtenanzahl `jclaw.compaction.threshold` (Standard: 20) überschreitet, werden ältere Nachrichten durch eine vom LLM erzeugte Zusammenfassung ersetzt. Die jüngsten `jclaw.compaction.retainCount` (Standard: 4) Nachrichten werden nie komprimiert. `LlmCompactionService` nutzt das konfigurierte ChatModel für die Zusammenfassung. Integration in `OllamaAiAdapter`: Vor jedem Agentenlauf wird geprüft, ob Compaction nötig ist — wenn ja, wird die Nachrichtenliste komprimiert, bevor der Prompt gebaut wird. Feature ist deaktiviert per Default (`jclaw.compaction.enabled=false`). Compaction ist optional (`ObjectProvider<CompactionService>`), sodass bestehende Tests unverändert bleiben.

> **Cron-Jobs (P1-12) getroffen:** Wiederkehrende Agent-Jobs über `jclaw.cron.*`-Konfiguration. `CronJob`-Record mit id, name, cronExpression (5-Feld-Format), prompt, contextId, enabled, lastRunAt, nextRunAt. `CronExpression`-Parser unterstützt `*`, Zahlen, Ranges (`1-5`), Steps (`*/5`, `1-10/2`), Listen (`1,3,5`) mit `nextExecutionAfter()`-Berechnung. `CronJobStore`-Port mit H2-Implementierung (`cron_job`-Tabelle). `CronSchedulerService` prüft periodisch auf fällige Jobs, führt Prompt über Listener aus, speichert lastRunAt/nextRunAt. REST-API: `GET|POST|PUT|DELETE /api/v1/cron-jobs`, `POST /api/v1/cron-jobs/{id}/execute` (manueller Trigger). Feature ist deaktiviert per Default (`jclaw.cron.enabled=false`). `@ConfigurationPropertiesScan` bindet `CronProperties` automatisch ein.

> **Channel-API (P3-01) getroffen:** Abstraktionsschicht für externe Nachrichten-Plattformen. `ChannelAdapter`-Port definiert `send()`, `isAvailable()`, `startReceiving()`/`stopReceiving()`. `ChannelStore`-Port verwaltet Channels, Bindungen und Nachrichten (H2-Implementierung mit `channel`, `channel_binding`, `channel_message`-Tabellen). `ChannelService` orchestriert CRUD, Senden via Adapter, Inbound-Verarbeitung und Bindungsverwaltung. Session-Bindung über `ChannelBinding` (DM oder Thread) mit External-ID-zu-Session-ID-Mapping. REST-API: `GET|POST|PUT|DELETE /api/v1/channels`, `POST /api/v1/channels/{id}/send`, `POST /api/v1/channels/{id}/inbound`, `GET|POST|DELETE /api/v1/channels/{id}/bindings`, `GET /api/v1/channels/adapters`. Feature ist deaktiviert per Default (`jclaw.channels.enabled=false`). Channel-Adapter (Telegram, Slack, Discord) sind eigenständige Bausteine (P3-02–P3-04).

> **Telegram-Adapter (P3-02) getroffen:** `TelegramChannelAdapter` implementiert den `ChannelAdapter`-Port für die Telegram Bot API über Long-Polling (`getUpdates`) und `sendMessage`. Konfiguration im `Channel.config`: `token` (Bot-Token, Pflicht), `pollTimeoutSeconds` (Lang-Polling-Timeout, Standard 30), `baseUrl` (Standard `https://api.telegram.org`). Senden: `POST /bot{token}/sendMessage` mit `chatId` aus `threadId`/`senderId`, erfasst die externe `message_id`. Empfang: `startReceiving` startet einen Daemon-Thread mit Long-Polling; eingehende Nachrichten werden zu `ChannelMessage.inbound` konvertiert (content, senderId/senderName, threadId=chatId, externalId=message_id) und an den `InboundMessageHandler` delegiert; der Offset wird über `update_id` fortgeschrieben (keine Duplikate). HTTP via `java.net.http.HttpClient` + Jackson 3 `ObjectMapper` (injektierbar für Tests). Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 9 Tests (Verfügbarkeit, senden, Fehlerfälle, Long-Polling, Empfang).

> **Slack-Adapter (P3-03) getroffen:** `SlackChannelAdapter` implementiert den `ChannelAdapter`-Port für Slack über **Socket Mode** (WebSocket) und die REST-API. Konfiguration im `Channel.config`: `token` (Bot-Token, Pflicht, z. B. `xoxb-…`), `baseUrl` (Standard `https://slack.com/api`). Senden: `POST {baseUrl}/chat.postMessage` mit `Authorization: Bearer {token}`, `channel` aus `threadId`/`senderId`, erfasst die externe `ts`. Empfang: `startReceiving` öffnet via `POST {baseUrl}/apps.connections.open` eine Socket-Mode-WebSocket-URL und verbindet sich mit dem eingebauten Jakarta-WebSocket-Client (`jakarta.websocket`, transitiv via `tomcat-embed-websocket` — keine neue Abhängigkeit). Eingehende `events_api`-Envelopes werden anhand der `envelope_id` bestätigt (Ack), `event_callback`-Nachrichten zu `ChannelMessage.inbound` konvertiert (content, senderId=user, threadId=channel, externalId=event_id/ts). Für Testbarkeit sind `WebSocketConnector` (funktional) und `SessionHandle` als injizierbare Abstraktion entkoppelt; die reale Verbindung übernimmt ein statischer Default (`connectSocket`) mit `@ClientEndpoint`-Klasse. Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 10 Tests (Verfügbarkeit, senden inkl. ts/Auth-Header/Body, Fehlerfälle, apps.connections.open, Envelope-Dispatch + Ack, ignorierbare Envelope-Typen).

> **Plugin-SDK-Stand (2026.6.34):** `before_agent_start`, Root-`openclaw/plugin-sdk`-Imports, `providerAuthEnvVars`/`channelEnvVars` werden nach dem 24.07.2026 entfernt. Die Node-Sidecar-Laufzeit (P4-01) muss gegen den **aktuellen** SDK-Stand bauen (Subpath-Imports, moderne Hook-Stages, `setup`-Deskriptoren); Details in `openclaw-compat.md` §3. Der Versionsstand der Referenz (2026.7.1 Stable / 2026.8.1-beta.2) ist in `openclaw-compat.md` §1.1 dokumentiert.

> Die Plugin-Laufzeit-Entscheidung (Node-Sidecar vs. GraalJS vs. Java-Reimplementation) ist getroffen: **Node-Sidecar**, siehe [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md). Das Bridge-Protokoll ist vollständig spezifiziert (siehe [bridge-protocol.md](bridge-protocol.md), P1-03).

## Definition of Done (Paritäts-Kriterien)

Ein Baustein gilt als paritätisch, wenn:

- das OpenClaw-Format **1:1** verstanden wird (Frontmatter, Manifest, Konfig-Schema),
- die Verhaltenssemantik übernommen ist (Deny-by-Default, Allowlisten, Timeouts, Retry/Fehlerverhalten),
- die Sicherheits-Policy mindestens OpenClaw-Niveau hat,
- der Baustein per REST/Test automatisierbar und mit Tests abgedeckt ist,
- die Doku (README bzw. dieses Dokument) den Status spiegelt.

## Nächste Schritte

1. **P3-04** Discord — Channel-Adapter (WebSocket) für Discord.
