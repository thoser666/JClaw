# JClaw → OpenClaw-Parität: Roadmap

Stand: 2026-09-02 · Basis: `docs/openclaw-compat.md` (Formatanalyse) · Referenz-Version: OpenClaw **2026.7.1** (Stable) / **2026.8.2** (aktuelle Linie, 01.09.2026) · Ziel: **100 % Parität** zu OpenClaw.

> **Automatischer Versions-Monitor:** Seit 2026-09-04 überwacht der GitHub-Workflow `.github/workflows/openclaw-monitor.yml` wöchentlich neue OpenClaw-Versionen und Community-Feature-Requests und legt bei Neuigkeiten ein Triage-Issue an (Release-/Community-Scan + Vision-Checkliste). Die zuletzt geprüfte Version liegt in `.github/state/openclaw-last-checked.txt`. Die manuelle "Referenz-Version"-Zeile oben wird über diesen Check aktuell gehalten (der erste Lauf erkennt z. B. die seit August neuere OpenClaw-Linie **2026.9.x**).

## Status-Legende

| Symbol | Bedeutung |
|---|---|
| ✅ | Erledigt (inkl. Tests) |
| 🔵 | In Arbeit |
| ⬜ | Geplant / nicht begonnen |
| 🚫 | Entscheidung ausstehend (Architektur) |

Prioritäten: 🔴 hoch · 🟡 mittel · 🟢 niedrig

---

## Thema A — Agent-Kern (Skills, Tools, Loop)

Hexagonale Basis; alles ✅. Kapselt den Kern-Agenten: Skills, Kern-Tools, MCP, Web, Policies, Fehlerbehandlung.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P0-01 | Skill-Loader | `SKILL.md`-Format (YAML-Frontmatter) aus `jclaw.agent.skills.dir` laden, sortieren, überspringen ungültiger Ordner | 🔴 | ✅ |
| P0-02 | Skill-Injektion | Aktivierte Skills per `jclaw.agent.skills.enabled` (Deny-by-Default) in den System-Prompt injizieren | 🔴 | ✅ |
| P0-03 | Kern-Tool: Rechner | `calculate` mit eigenem sicheren Ausdrucks-Parser | 🔴 | ✅ |
| P0-04 | Kern-Tool: Datum/Zeit | `getCurrentDateTime` mit optionaler Zeitzone | 🟡 | ✅ |
| P0-05 | Kern-Tool: Datei | `readFile`, `listDirectory`, `writeFile` mit Workdir-Scoping + Traversal-Schutz | 🔴 | ✅ |
| P0-06 | Kern-Tool: Datei-Suche | `glob` (Glob-Muster) und `grep` (Regex) innerhalb des Workdirs | 🔴 | ✅ |
| P0-07 | Kern-Tool: Shell | `runCommand` mit Workdir, Timeout, Output-Limit (opt-in, Deny-by-Default) | 🟡 | ✅ |
| P0-11 | Agent-Loop | Tool-Calling-Loop mit Iterationslimit + `AgentLoopLimitExceededException` | 🔴 | ✅ |
| P0-12 | Fehlerbehandlung | Globaler `@RestControllerAdvice` (400/500) | 🟡 | ✅ |
| P1-04 | MCP-Client | `mcp.servers`-Unterstützung: externe Model Context Protocol-Server als Tools integrieren (`jclaw.mcp.servers.*`, HTTP + STDIO, Deny-by-Default) | 🔴 | ✅ |
| P1-05 | Web-Tools | `web_fetch` (mit `allowedDomains`-Policy) und `web_search` | 🟡 | ✅ |
| P1-06 | Kern-Tool: Patch | `apply_patch` für strukturierte Datei-Änderungen | 🟡 | ✅ |
| P1-08 | Tool-Policies | Allow-/Denyliste je Agent via `jclaw.agent.tools.allow`/`.deny` (Deny-by-Default, Deny schlägt Allow; deaktivierte Tools erscheinen nicht im Tool-Schema) | 🟡 | ✅ |

## Thema B — Memory, Persistenz & Sessions

Konversations-, Session- und Wissens-Memory; alles bis auf die Memory-Erweiterungen lieferbar.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P0-08 | Konversations-Memory | Message-Window je `contextId`, Speicherung/Abfrage/Löschung über REST | 🔴 | ✅ |
| P0-09 | Persistenz | H2-JDBC-`ChatMemory` (`chat_message`-Tabelle, überlebt Neustarts) | 🔴 | ✅ |
| P0-10 | Skill-/Konversations-API | `GET /api/v1/skills`, `GET|DELETE /api/v1/conversations/{contextId}` | 🟡 | ✅ |
| P1-09 | Session-Konzept | Von `contextId` auf Sessions erweitern (Reset-Strategien `daily`/`idle`, generierte Titel, REST-API); Session-first-UI seit 2026.7.1 als Referenz. Scope-Abgrenzung: Thread-Bindings, dmScope, Session-Gruppen, Transcript-Export und Kontext-Verbrauch sind P2-04 (Gateway) | 🟡 | ✅ |
| P1-10 | Compaction | Kontext-Kompression bei Session-Grenzen | 🟢 | ✅ |
| P4-02 | Wissen-Memory & Open Memory Vault | **Memory als Asset statt Cache:** Rohe Nachrichten bleiben dauerhaft in H2 — Compaction (P1-10) komprimiert nur den LLM-Context, nie den Speicher. **Vault-Write + List ✅:** Memory als Markdown+YAML-Frontmatter (`title`/`type`/`created`/`tags`/`source`) nach `jclaw.memory.vault.dir` materialisieren (idempotent je conversationId) und auflisten; Rotest via `POST /api/v1/memory/{contextId}/sync` + `GET /api/v1/memory`, menschenlesbar via Tolaria/Obsidian. **Read-Back (Watcher-Sync) ✅:** `MemoryVaultWatcher` erkennt `.md`-Änderungen im Vault-Ordner und ingestet sie über `ConversationStore.saveAll` zurück in H2 (symmetrisches Markdown-Format via `renderMessages`/`parseMessages`). **Noch offen:** semantisches Embedding-Retrieval (`kind: "memory"`), Writer-Trigger in den Compaction-Flow. H2 bleibt Quelle der Wahrheit (kein Dual-Write-Problem). | 🟡 | 🟢 |
| P4-10 | Backup & Restore | `jclaw backup`-Semantik nach OpenClaw-Vorbild (`create|list|verify|restore`, globale + Per-Agent-Snapshots, 2026.8.1-beta.2) | 🟢 | ⬜ |
| P4-15 | Shared Memory Pools | **Neu:** Mehrere Agents teilen sich ein Langzeit-Memory (Vektor + BM25 über den dauerhaften Verlauf) statt siloed Memory je Agent — Industrie-Trend, OpenClaw bietet es standardmäßig nicht | 🟢 | ⬜ |

## Thema C — Konfiguration, Gateway & Control-UI

Steuerungsebene: Konfig, Auth, Hot-Reload, Web-UI, Themes.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P2-01 | JSON5-Konfig | `openclaw.json`-Format (Kommentare, Trailing Commas), `$include`, `${VAR}`-Substitution | 🔴 | ✅ |
| P2-02 | Schema-Validierung | Strikte Validierung; Gateway startet bei ungültiger Konfiguration nicht | 🔴 | ✅ |
| P2-03 | Hot-Reload | Auto-Detect + manuelles `config.apply`; laufende Agents behalten ihre Config | 🟡 | ✅ |
| P2-04 | Gateway-Steuerung | Session-Gruppen, Transcript-Export, Gateway-Status- & Info-API | 🟡 | ✅ |
| P2-05 | Control-UI | Web-Oberfläche (statische SPA, kein Build-Schritt) über die REST-API: Agent, Konversationen, Skills, Plugins | 🟢 | ✅ |
| P2-06 | Auth | Gateway-Authentifizierung (API-Token, Bearer-Header, SHA-256-Hash, Deny-by-Default) | 🟡 | ✅ |
| P2-09 | Color-Schemes | Light/Dark-Theme via CSS-Variablen (`prefers-color-scheme` + manueller Toggle), Designsystem für die Control-UI | 🟡 | ✅ |

## Thema D — Automations & Workflows

Zeit- und eventgetriebene Agent-Ausführung (Cron, Hooks) plus neue OpenClaw-Features (Background-Sessions, Goals, queue-basierte Follow-ups).

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P1-11 | Hooks | `HOOK.md`-Scripts + Lifecycle-Events (before_tool_call, before_agent_run, …) via Script-Runner; Hook-Stage-Migration beachten (`before_agent_start`/SDK-Root-Imports seit 2026.6.34 entfernt) | 🟡 | ✅ |
| P1-12 | Cron-Jobs | Wiederkehrende Agent-Jobs (`cron.*`-Konfiguration) | 🟢 | ✅ |
| P4-07 | Goals & Queues | **Neu:** Goal-Erstellung/-Bearbeitung direkt im Composer, Follow-up-Queue-Persistenz über Gateway-Neustarts (OpenClaw 2026.8.2; PR #82572 bringt Queue-Persistenz) | 🟡 | ⬜ |
| P4-08 | Background-Sessions | **Neu:** Neue Session im Hintergrund starten, ohne Seitenwechsel; Completion-Benachrichtigung (2026.8.2) | 🟢 | ⬜ |

## Thema E — Channels (100 %-Parität)

OpenClaw-Kernfeature: Nachrichten von/nach externen Plattformen.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P3-01 | Channel-API | Abstraktion (send/receive) + Session-Bindung (DM-/Thread-Bindung) | 🔴 | ✅ |
| P3-02 | Telegram | Channel-Adapter (Polling/Webhook) | 🟡 | ✅ |
| P3-03 | Slack | Channel-Adapter (Socket Mode) | 🟡 | ✅ |
| P3-04 | Discord | Channel-Adapter (WebSocket) | 🟡 | ✅ |
| P3-05 | WhatsApp | Channel-Adapter (Cloud API) | 🟡 | ✅ |
| P3-06 | Weitere Channels | Buzz (Nostr), ClickClack, IRC ✅, Google Chat, Synology Chat, Mattermost, Feishu u. a. | 🟢 | 🟡 |
| P3-07 | Media-Message-Support | **Neu:** Spoken-Replies (TTS-Sprachnachrichten) in privaten Replies (Discord/Telegram, 2026.8.2) — Senden von Audio über den jeweiligen Adapter | 🟢 | ⬜ |
| P3-08 | Ingress-Monitor-Abstraktion | Durable admission, polling, pruning, claim-identity validation, adoption handoff, shutdown (SDK-Vorbild, family-readiness) | 🟢 | ⬜ |

## Thema F — Browser, Computer Use & Talk (OpenClaw 2026.8.x)

**Neues Themenfeld** für die 2026.8.x-Feature-Klassen, die in der bisherigen Roadmap fehlten.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P4-11 | Browser-Steuerung | **Neu:** Browser-Steuerung ohne laufendes Gateway (Chrome-Extension weckt lokales Relay, 2026.8.2) + Browser-/Computer-Use-Tooling | 🟡 | ⬜ |
| P4-12 | Computer Use | **Neu:** Bildschirm-/Interface-Steuerung auf Desktop-Ebene | 🟢 | ⬜ |
| P4-13 | Talk (Realtime-Voice) | **Neu:** Browser-basiertes Talk: Mikrofon-Permission, geordnete Turns, Interruption-Recovery (OpenAI WebRTC / Google Live, 2026.8.1/8.2) | 🟢 | ⬜ |
| P4-14 | Voice Calls & TTS-Personas | **Neu:** Call-Turn-Taking, TTS-Personas via SecretRefs, Multilinguale Auto-Sprach-Erkennung (2026.8.2) | 🟢 | ⬜ |

## Thema G — Multi-Agent & Orchestrierung

Sub-Agenten und deren Verwaltung.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P1-07 | Kern-Tool: Agent | `spawn_agent` / Multi-Agent-Subprozesse (Deny-by-Default, max-depth-Limit) | 🟢 | ✅ |
| P4-01 | Plugin-Laufzeit | Node-Sidecar führt `definePluginEntry`/`defineChannelPluginEntry` aus (setzt P1-03 voraus) | 🔴 | 🚫 |
| P4-03 | Media-Provider | Speech/Media-Provider (TTS/STT) | 🟢 | ⬜ |
| P4-04 | Provider-Abstraktion | Modell-Provider über Ollama hinaus (OpenAI-kompatibel, Anthropic, …) via Spring AI; Referenz-Stand 2026.8.x: GPT-5.6 (+ Sol/Terra/Luna), Claude Sonnet 5, Meta Muse Spark 1.1, Featherless, ClawRouter, lokales Setup (Ollama/llama.cpp/LM Studio) | 🟡 | ⬜ |

## Thema H — Plugin-Ökosystem & SDK-Anbindung

Manifeste, Bridge, Laufzeit; Node-Sidecar als Zielarchitektur.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P1-01 | Plugin Control-Plane | Manifeste lesen/validieren (`openclaw.plugin.json` + Agent-Plugins/Codex/Claude/Cursor), ohne Codeausführung; `GET /api/v1/plugins` | 🔴 | ✅ |
| P1-02 | Architektur-Entscheidung | **Node-Sidecar bestätigt** (JSON-RPC 2.0 über stdio). Spike validiert Java ↔ Node-Kommunikation. Siehe [ADR-0001](adr/0001-node-sidecar-plugin-runtime.md) | 🔴 | ✅ |
| P1-03 | Bridge-Protokoll | Vollständige JSON-RPC-Spezifikation (Framing, Methoden-Katalog, Fehlercodes, Timeouts, Restart) — [bridge-protocol.md](bridge-protocol.md); Bridge als verwaltbarer Dienst (Handshake, Call-/Ready-Timeout, `restart()`) | 🔴 | ✅ |
| P4-09 | Plugin-Security-Maßnahmen | **Neu:** Secret-Egress-Host-Binding (fail-closed), Plugin-Install-Provenance, **Credential-Leak-Guardrail** (Token-/Secret-Teile erscheinen nie in Session-Anzeigen/Outbound-Nachrichten; reagiert auf OpenClaw #32970), Caps für feindliche Response-Größen (2026.6.34/8.1) | 🟡 | ⬜ |
| P4-16 | Stable API & Versionsdisziplin | **Neu:** Schema-/API-Versionierung + Deprecation-Zyklen als Gegenmodell zu OpenClaws breaking-change-Kultur (SDK-Stage-Removals, `Full Release Validation`-Failures); CI-Gate: keine Promotions ohne grüne Referenz-Testsuite | 🟡 | ⬜ |

## Thema I — Memory-Wissen & Skill-Workshop

Semantisches Memory und Skill-Ops abseits des Kern-Loaders.

| ID | Baustein | Beschreibung | Priorität | Status |
|---|---|---|---|---|
| P4-05 | Paritäts-Testsuite | Automatisierte Konformitäts-Checks: Manifeste, Konfig, Hooks, Tool-Schemas gegen OpenClaw-Referenz; **Guardrail-Tests**: kein Credential-Leak in Outbound, Egress-Binding, Deny-by-Default | 🟡 | ⬜ |
| P4-06 | Skill Workshop | Vorschlags-Verwaltung (`skills.workshop.*`): Proposals, apply/reject/quarantine, `approvalPolicy: "pending"` (seit 2026.6.1) | 🟢 | ⬜ |

---

## Release-Plan

Die Bausteine werden nicht einzeln, sondern in Versionen mit einem in sich geschlossenen, testbaren Ergebnis gebündelt. Die Reihenfolge folgt den Prioritäten (🔴 zuerst) und den Abhängigkeiten. Bis zur vollen Parität gilt SemVer (`0.x`); `1.0.0` = 100 % Parität.

| Version | Thema | Bausteine | Ziel / Wert |
|---|---|---|---|
| **0.1.0** | Agent-Kern | ~~P1-06~~ ✅ `apply_patch`, ~~P1-07~~ ✅ `spawn_agent`, ~~P1-08~~ ✅ Tool-Policies, ~~P1-09~~ ✅ Session-Konzept | Verlässlicher Einzel-Agent mit Policy-, Session- und Multi-Agent-Modell |
| **0.2.0** | Konfiguration & Gateway | ~~P2-01~~ ✅ JSON5-Konfig, ~~P2-02~~ ✅ Schema-Validierung, ~~P2-03~~ ✅ Hot-Reload, ~~P2-04~~ ✅ Gateway-Steuerung, ~~P2-06~~ ✅ Auth, ~~P2-09~~ ✅ Color-Schemes | Steuerungsebene als Anker für Hooks, Cron und Channels |
| **0.3.0** | Multi-Agent & Plugins | P4-01 Plugin-Laufzeit, P4-04 Provider-Abstraktion, P4-09 Plugin-Security | OpenClaw-Parität beim Agent-Verhalten |
| **0.4.0** | Channels | P3-06 Weitere Channels (IRC ✅), P3-07 Media-Message-Support, P3-08 Ingress-Monitor, ~~P2-05~~ ✅ Control-UI | Nutzbares Multi-Plattform-Produkt |
| **0.5.0** | Automations & Memory | P4-07 Goals & Queues, P4-08 Background-Sessions, P4-02 Wissen-Memory, P4-10 Backup & Restore | Zeit-/eventgetriebene Agent-Ausführung + Wissensspeicher |
| **0.6.0** | Browser, Talk & Voice | P4-11 Browser-Steuerung, P4-12 Computer Use, P4-13 Talk, P4-14 Voice Calls & TTS-Personas | OpenClaw-2026.8.x-Features für Realtime & Desktop-Steuerung |
| **1.0.0** | 100 % Parität | ~~P1-11~~ ✅ Hooks, ~~P1-10~~ ✅ Compaction, P4-03 Media-Provider, P4-05 Paritäts-Testsuite, P4-06 Skill Workshop, P4-01 Plugin-Laufzeit | Feature-Parität, erste stabile Version |

Anmerkungen:

- `0.1.0-SNAPSHOT` ist die aktuelle Entwicklungsversion (siehe `pom.xml`); abgeschlossene Versionen werden als Release getaggt.
- Abhängigkeiten: P1-12 (Cron) setzt das Session-Konzept (P1-09) und das Gateway voraus, P4-01 setzt die Bridge (P1-03) voraus, Channels setzen die Gateway-Steuerung (P2-04) voraus, P4-13/14 (Talk/Voice) setzen P4-03 (Media-Provider) voraus.

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

> **Open Memory Vault (P4-02) getroffen (Vault-Write/List):** Memory wird als **Asset** statt Cache behandelt — die Vault-Dateien leben ausserhalb des LLM-Contexts und überstehen Compaction/Neustarts. Umgesetzt mit `MemoryDocument`-Record, `MemoryVaultStore`-Port (out), `MarkdownMemoryVault`-Adapter (Markdown+YAML-Frontmatter, idempotent je conversationId) und `MemoryVaultService` (orchestriert; materialisiert die Konversation aus `ConversationStore`). Feature-Gating wie bei Compaction/Channels: Adapter ist `@ConditionalOnProperty(jclaw.memory.vault.enabled=true)`, der Service injiziert `ObjectProvider<MemoryVaultStore>` und no-opt, wenn deaktiviert. REST-API: `POST /api/v1/memory/{contextId}/sync`, `GET /api/v1/memory`. **H2 bleibt Quelle der Wahrheit** — der Vault ist ein idempotenter Auszug, kein Ersatz (kein Dual-Write-Problem).

> **Open Memory Vault Read-Back (P4-02) getroffen:** `MemoryVaultWatcher` (Muster von `Json5ConfigWatcher`) überwacht den Vault-Ordner auf `.md`-CREATE/MODIFY (Debounce 500 ms). Bei einer Änderung liest `MemoryVaultIngestService` das Dokument via `MarkdownMemoryVault.readDocument` und reicht die Nachrichten über das neue `ConversationStore.saveAll` an H2 weiter (Mapping role-Name → Spring-AI-`Message` in `ChatMemoryConversationStore`). Das Markdown-Nachrichtenformat ist zentralisiert (`renderMessages`/`parseMessages`, symmetrisch), sodass Write- und Read-Pfad garantiert zusammenpassen. Der Ingest hängt aktuell an (`ChatMemoryRepository.saveAll` = Append) — ein vollständiges *Ersetzen* der Konversation (delete+save) ist als Verfeinerung vorgemerkt. Der Read-Back ist Best-effort; H2 bleibt Quelle der Wahrheit. Offen: Compaction-Flow-Trigger, semantisches Retrieval.

> **Cron-Jobs (P1-12) getroffen:** Wiederkehrende Agent-Jobs über `jclaw.cron.*`-Konfiguration. `CronJob`-Record mit id, name, cronExpression (5-Feld-Format), prompt, contextId, enabled, lastRunAt, nextRunAt. `CronExpression`-Parser unterstützt `*`, Zahlen, Ranges (`1-5`), Steps (`*/5`, `1-10/2`), Listen (`1,3,5`) mit `nextExecutionAfter()`-Berechnung. `CronJobStore`-Port mit H2-Implementierung (`cron_job`-Tabelle). `CronSchedulerService` prüft periodisch auf fällige Jobs, führt Prompt über Listener aus, speichert lastRunAt/nextRunAt. REST-API: `GET|POST|PUT|DELETE /api/v1/cron-jobs`, `POST /api/v1/cron-jobs/{id}/execute` (manueller Trigger). Feature ist deaktiviert per Default (`jclaw.cron.enabled=false`). `@ConfigurationPropertiesScan` bindet `CronProperties` automatisch ein.

> **Channel-API (P3-01) getroffen:** Abstraktionsschicht für externe Nachrichten-Plattformen. `ChannelAdapter`-Port definiert `send()`, `isAvailable()`, `startReceiving()`/`stopReceiving()`. `ChannelStore`-Port verwaltet Channels, Bindungen und Nachrichten (H2-Implementierung mit `channel`, `channel_binding`, `channel_message`-Tabellen). `ChannelService` orchestriert CRUD, Senden via Adapter, Inbound-Verarbeitung und Bindungsverwaltung. Session-Bindung über `ChannelBinding` (DM oder Thread) mit External-ID-zu-Session-ID-Mapping. REST-API: `GET|POST|PUT|DELETE /api/v1/channels`, `POST /api/v1/channels/{id}/send`, `POST /api/v1/channels/{id}/inbound`, `GET|POST|DELETE /api/v1/channels/{id}/bindings`, `GET /api/v1/channels/adapters`. Feature ist deaktiviert per Default (`jclaw.channels.enabled=false`). Channel-Adapter (Telegram, Slack, Discord, WhatsApp, IRC) sind eigenständige Bausteine (P3-02–P3-06).

> **Telegram-Adapter (P3-02) getroffen:** `TelegramChannelAdapter` implementiert den `ChannelAdapter`-Port für die Telegram Bot API über Long-Polling (`getUpdates`) und `sendMessage`. Konfiguration im `Channel.config`: `token` (Bot-Token, Pflicht), `pollTimeoutSeconds` (Lang-Polling-Timeout, Standard 30), `baseUrl` (Standard `https://api.telegram.org`). Senden: `POST /bot{token}/sendMessage` mit `chatId` aus `threadId`/`senderId`, erfasst die externe `message_id`. Empfang: `startReceiving` startet einen Daemon-Thread mit Long-Polling; eingehende Nachrichten werden zu `ChannelMessage.inbound` konvertiert (content, senderId/senderName, threadId=chatId, externalId=message_id) und an den `InboundMessageHandler` delegiert; der Offset wird über `update_id` fortgeschrieben (keine Duplikate). HTTP via `java.net.http.HttpClient` + Jackson 3 `ObjectMapper` (injektierbar für Tests). Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 9 Tests (Verfügbarkeit, senden, Fehlerfälle, Long-Polling, Empfang).

> **Slack-Adapter (P3-03) getroffen:** `SlackChannelAdapter` implementiert den `ChannelAdapter`-Port für Slack über **Socket Mode** (WebSocket) und die REST-API. Konfiguration im `Channel.config`: `token` (Bot-Token, Pflicht, z. B. `xoxb-…`), `baseUrl` (Standard `https://slack.com/api`). Senden: `POST {baseUrl}/chat.postMessage` mit `Authorization: Bearer {token}`, `channel` aus `threadId`/`senderId`, erfasst die externe `ts`. Empfang: `startReceiving` öffnet via `POST {baseUrl}/apps.connections.open` eine Socket-Mode-WebSocket-URL und verbindet sich mit dem eingebauten Jakarta-WebSocket-Client (`jakarta.websocket`, transitiv via `tomcat-embed-websocket` — keine neue Abhängigkeit). Eingehende `events_api`-Envelopes werden anhand der `envelope_id` bestätigt (Ack), `event_callback`-Nachrichten zu `ChannelMessage.inbound` konvertiert (content, senderId=user, threadId=channel, externalId=event_id/ts). Für Testbarkeit sind `WebSocketConnector` (funktional) und `SessionHandle` als injizierbare Abstraktion entkoppelt; die reale Verbindung übernimmt ein statischer Default (`connectSocket`) mit `@ClientEndpoint`-Klasse. Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 10 Tests (Verfügbarkeit, senden inkl. ts/Auth-Header/Body, Fehlerfälle, apps.connections.open, Envelope-Dispatch + Ack, ignorierbare Envelope-Typen).

> **Discord-Adapter (P3-04) getroffen:** `DiscordChannelAdapter` implementiert den `ChannelAdapter`-Port für Discord über den **Gateway-WebSocket** und die REST-API. Konfiguration im `Channel.config`: `token` (Bot-Token, Pflicht), `baseUrl` (Standard `https://discord.com/api/v10`), `intents` (optional, Standard 4609 = GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES). Senden: `POST {baseUrl}/channels/{channelId}/messages` mit `Authorization: Bot {token}`, `channelId` aus `threadId`/`senderId`, erfasst die externe `id`. Empfang: `startReceiving` ermittelt über `GET {baseUrl}/gateway` die Gateway-URL und verbindet sich mit dem eingebauten Jakarta-WebSocket-Client; beim `Hello`-Frame (op 10) wird der `Identify`-Frame (op 2) mit Token + Intents gesendet, auf Heartbeat-Anfragen (op 1) wird mit einem Heartbeat geantwortet, und `MESSAGE_CREATE`-Dispatches (op 0) werden zu `ChannelMessage.inbound` konvertiert (content, senderId/senderName=author, threadId=channel_id, externalId=id). Für Testbarkeit sind `WebSocketConnector` (funktional) und `SessionHandle` als injizierbare Abstraktion entkoppelt; die reale Verbindung übernimmt ein statischer Default (`connectSocket`) mit `@ClientEndpoint`-Klasse. Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 11 Tests (Verfügbarkeit, senden inkl. id/Auth-Header/Pfad/Body, Fehlerfälle, /gateway, Identify beim Hello, Heartbeat-Antwort, MESSAGE_CREATE-Dispatch, ignorierbare Frames).

> **WhatsApp-Adapter (P3-05) getroffen:** `WhatsAppChannelAdapter` implementiert den `ChannelAdapter`-Port für WhatsApp über die **Meta WhatsApp Cloud API** (Graph API). Konfiguration im `Channel.config`: `token` (Meta-System-User-Token, Pflicht), `phoneNumberId` (WhatsApp Business Phone Number ID, Pflicht), `graphUrl` (Standard `https://graph.facebook.com/v21.0`), `verifyToken` (optional, für den Meta-Webhook-Handshake). Senden: `POST {graphUrl}/{phoneNumberId}/messages` mit `Authorization: Bearer {token}` und `{"messaging_product":"whatsapp","to":…,"text":{"body":…}}`; `to` aus `threadId`/`senderId`, erfasst die externe `messages[0].id`. Empfang ist **push-basiert** (Meta-Webhook) — `startReceiving`/`stopReceiving` bleiben Default-No-ops; der Adapter stellt `verifyWebhook()` (Hub-Challenge-Handshake) und `inboundFromWebhook()` (Parsen des Meta-Payloads in `ChannelMessage.inbound`; content, senderId/threadId=wa_id, senderName=profile.name, externalId=id) bereit. Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 10 Tests (Verfügbarkeit, senden inkl. id/Auth-Header/Pfad/Body, Fehlerfälle, Webhook-Verifikation, Inbound-Parsing, ignorierbare non-Message-Payloads).

> **IRC-Adapter (P3-06) getroffen:** `IrcChannelAdapter` implementiert den `ChannelAdapter`-Port für **IRC** (RFC 1459/2812) über eine direkte TCP-Verbindung. Konfiguration im `Channel.config`: `server` (Hostname, Pflicht), `port` (Standard 6667), `nick` (Standard `jclaw`), `channel` (Standard `#general`; fehlendes `#`/`&`-Präfix wird ergänzt), `nickservPassword` (optional, wird als `PRIVMSG NickServ :IDENTIFY …` gesendet). Senden: `PRIVMSG <target> :<text>` — `target` aus `threadId`/`senderId`. Empfang: `startReceiving` verbindet per TCP-Socket, sendet `NICK`/`USER`/`JOIN`, startet einen Daemon-Lesethread und parsed eingehende `PRIVMSG`-Zeilen in `ChannelMessage.inbound` (content, senderId=`nick`, senderName=`nick!user@host`, threadId=Zielchannel); alle anderen Zeilen (PING, numerische Replies, …) werden ignoriert. Für Testbarkeit sind `IrcConnector` (funktional) und `IrcSession` (readLine/writeLine/close) als injizierbare Abstraktion entkoppelt; die reale Verbindung übernimmt ein statischer Default (`connect`) mit `SocketIrcSession`. Keine neue Dependency (Standard-Java-Sockets). Adapter ist als `@Component` mit `@ConditionalOnProperty(jclaw.channels.enabled=true)` registriert. 10 Tests (Verfügbarkeit, Senden inkl. PRIVMSG, Fehlerfälle Ziel/keine aktive Session, Join/NickServ-Handshake, PRIVMSG-Parsing, QUIT+Close, Nicht-PRIVMSG-Zeilen).

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

1. **P3-06/P3-08** Weitere Channel-Adapter (**IRC ✅**) — verbleibend: Buzz, ClickClack, Google Chat, Mattermost, Feishu, Email, X, … — und die **Ingress-Monitor-Abstraktion** aus dem OpenClaw-Plugin-SDK nachbilden (durable admission, polling, pruning, claim-identity validation, adoption handoff, shutdown); dabei **P3-07** Media-Message-Support berücksichtigen.
2. **P4-01** Node-Sidecar-Plugin-Laufzeit — gegen den aktuellen Plugin-SDK-Stand (Subpath-Imports, moderne Hook-Stages), mit **P4-09** Security-Maßnahmen.
3. **P4-07/P4-08** Automations-Erweiterungen aus OpenClaw 2026.8.x (Goals & Queues, Background-Sessions).
4. **P4-11–P4-14** Browser-/Talk-/Voice-Features — Referenz OpenClaw 2026.8.2 (Browser-Steuerung ohne Gateway, Realtime-Talk, TTS-Personas).
5. **Versions-Monitor-Triage:** Vom wöchentlichen OpenClaw-Monitor erzeugte Issues regelmäßig durchgehen (neue Stable-Linie **2026.9.x**, neue Features/Community-Wünsche) und gegen die oben stehenden Referenz-Versionen + P-Items aktualisieren — siehe README "OpenClaw-Versionsmonitor".
