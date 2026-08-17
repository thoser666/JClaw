# OpenClaw-Kompatibilität — Analyse der Formate und Architektur

Stand: 2026-08-15 · Referenz: OpenClaw **2026.7.1** (Stable) / **2026.6.34** (Extended-Stable) / **2026.8.1-beta.2** · Quellen: `github.com/openclaw/openclaw` (`/docs`, Releases/CHANGELOG), `docs.openclaw.ai` (Plugins, Manifest, Hooks, Gateway/Configuration), AgentSkills-Spec (`agentskills.io`), Agent-Plugins-Standard (`agent-plugins.org`).

## Ziel

JClaw (Java/Spring-Boot-4-Port von OpenClaw) soll OpenClaw-Plugins und -Skills **1:1 übernehmen** können. Dieses Dokument beschreibt die OpenClaw-Formate und leitet ab, was eine Kompatibilitätsschicht leisten muss — als Entscheidungsbasis für den Integrationsweg (Node-Sidecar vs. GraalJS vs. Java-Reimplementation).

---

## 1. OpenClaw im Überblick

OpenClaw ist ein **Node.js/TypeScript-Framework** (ESM, pnpm-Workspace, npm-Paket `openclaw`, Node ≥ 22.22.3 / 24.15 / 25.9). Kernkonzepte:

| Konzept | Beschreibung |
|---|---|
| **Gateway** | Lokaler Kontrollserver (localhost), RPC-API, Konfig-Hot-Reload, verwaltet Sessions, Plugins, Hooks, Cron. |
| **Agent-Harness** | Treibt den Agent-Loop (Befehle → Modell → Tool-Calls). Eingebettet (Standalone), Codex- oder Copilot-basiert. |
| **Channels** | WhatsApp, Telegram, Slack, Discord, X, Signal, Email u. v. m. — Nachrichten zwischen Platformen und Agent-Loop. |
| **Tools** | Kern-Tools (Datei-/Web-/Shell-Zugriff) + Plugin-Tools + MCP-Server. |
| **Skills** | Markdown-Bündel mit YAML-Frontmatter (AgentSkills-Spec), werden als Prompt-Kontext injiziert. |
| **Plugins** | TypeScript-Pakete (npm / ClawHub), erweitern Agent um Tools, Commands, Hooks, Channels, Provider. |
| **Memory/Sessions** | Session-basiert (DM-/Thread-Bindung, Reset-Strategien), konversationell + Wissens-/Embedding-Memory, SQLite. |

Implikation: Der **Agent-Kern selbst ist in Java nachbildbar**; die **Plugins sind nur über JavaScript/Node ausführbar** (sie importieren die `openclaw`-Runtime und registrieren sich zur Laufzeit).

### 1.1 Versionsstand und neue Features

OpenClaw folgt Kalender-Versionierung (`YYYY.M.PATCH`) mit den Kanälen Stable, Beta und Dev (Extended-Stable = Wartungslinie):

| Channel | Version | Datum | Bedeutung |
|---|---|---|---|
| **Stable** | **2026.7.1** (Korrekturreleases `-1`/`-2`) | 13.07.2026 / Korrekturen 04.08.2026 | Aktuelle stabile Linie |
| Extended-Stable | **2026.6.34** | 08.08.2026 | Wartungslinie (`extended-stable` npm/Container) |
| Beta | **2026.8.1-beta.2** | 15.08.2026 | Neue Features, aber **Full Release Validation fehlgeschlagen → nicht promotion-ready** |

Für die JClaw-Parität relevante Änderungen seit der Erstanlage (2026-08-08):

- **Session-first Control-UI (2026.7.1):** Sessions sind die primäre Navigations-Einheit — durchsuchbare Session-Liste, **Session-Gruppen**, **generierte Titel**, **Transcript-Export**, Anzeige von Kontext-Verbrauch sowie Modell-/Thinking-Steuerung je Session. → betrifft P1-09 (Session-Konzept) und die Session-Steuerung der JClaw-UI (P2-04).
- **Neue Channels:** Zusätzlich zu Telegram/Slack/Discord/WhatsApp/X/Signal/Email gibt es u. a. **Buzz (Nostr)**, **ClickClack**, **IRC**, **Google Chat**, **Synology Chat**, **Mattermost**, **Feishu** (Stand 2026.8.x). Im Plugin-SDK gibt es dafür einen gemeinsamen **Ingress-Monitor** (durable admission, polling, pruning, claim-identity validation, adoption handoff, shutdown) — Channel-Adapter sollen diesen Lifecycle nachbilden statt eigener Logik. → betrifft P3.
- **Plugin-SDK-Migration (angekündigt in 2026.6.34):** Hook-Stage `before_agent_start`, Root-Imports `openclaw/plugin-sdk`, `providerAuthEnvVars` und `channelEnvVars` werden **nach dem 24.07.2026 entfernt**. Migrationsziel: moderne Hook-Stages, fokussierte SDK-Subpath-Imports, Manifest-`setup`-Deskriptoren. → betrifft §3 und P4-01.
- **Skill Workshop (2026.6.1, ausgebaut in 2026.8.x):** `skills.workshop.*` verwaltet Skill-Vorschläge (apply/reject/quarantine); `skills.workshop.approvalPolicy: "pending"` schaltet ein Approval-Gate ein. → reine Control-Plane, in Java nachbildbar; betrifft §2 und P0-01.
- **Neue Provider/Modelle (2026.7.1/8.1):** GPT-5.6 (inkl. Ultra/Sol/Terra/Luna mit atomarem Laufzeitwechsel), Claude Sonnet 5, Meta Muse Spark 1.1, Featherless, ClawRouter (dynamische Model-Discovery, Budget-Reporting); lokales Setup für Ollama/llama.cpp/LM Studio. → betrifft P4-04 (Provider-Abstraktion).
- **Speicher & Backup:** Schema-Migration zu SQLite (2026.6.1); `openclaw backup sqlite create|list|verify|restore` mit globalen und Per-Agent-Snapshots (2026.8.1-beta.2). → JClaw nutzt H2; die Backup-/Recovery-Semantik dient als Referenz.
- **Security/Stability (2026.6.34/8.1):** Secret-Egress-Host-Binding (fail-closed), Plugin-Install-Provenance (`--force` für beliebige ausführbare Quellen), Browser-/Netzwerk-Boundary-Hardening, Caps für feindliche Response-Größen. → Security-Policies als Mindestniveau übernehmen.

---

## 2. Skills (AgentSkills-Spec)

### Format
- Skill = Verzeichnis mit **`SKILL.md`** (Akzeptierte Namen: `SKILL.md`, `skill.md`, veraltete `skills.md`).
- **YAML-Frontmatter** (zwischen `---`) + Markdown-Body.
- Fallback: Single-Line-YAML-Parser, wenn der reguläre Parser fehlschlägt; `metadata` wird als **JSON5** geparst.

### Felder
| Feld | Pflicht | Bedeutung |
|---|---|---|
| `name` | ja | Eindeutiger Name. |
| `description` | ja | Was der Skill tut (wird dem Modell präsentiert). |
| `homepage` | nein | Referenz. |
| `user-invocable` | nein | Nur bei Bedarf aufrufbar. |
| `disable-model-invocation` | nein | Nur explizit aufrufbar. |
| `license`, `version` | nein | Metadaten. |
| `trigger` | nein | Text-Trigger. |
| `allowed-tools` / `tools` | nein | Tool-Allowlist/-Denylist für den Skill-Kontext. |
| `metadata.openclaw` | nein | `requires.env`, `requires.bins`, `primaryEnv`, `envVars`, `install` (brew/node/go/uv), `nix`, `config`; Aliase `clawdbot`/`clawdis`. |

### Ordnerstruktur (Konvention)
`scripts/`, `references/`, `assets/`, `agents/` — alle per `{baseDir}`-Platzhalter in Templates referenzierbar.

### Ladeorte & Präzedenz (höhere gewinnt)
`<workspace>/skills` > `~/.agents/skills` > `~/.openclaw/skills` > gebündelte Skills > `skills.extraDirs`.

### Konfiguration
`skills.entries.*` (direkte Pfad-/Ordner-Registrierung), `agents.defaults.skills` (Skill-Auswahl je Agent), Agent-Allowlisten/Denylisten.

Seit 2026.6.x ergänzt der **Skill Workshop** (`skills.workshop.*`) eine Vorschlags-Verwaltung: Der Agent erzeugt Skill-Proposals, die per Control-UI/CLI **angewendet, abgelehnt oder quarantäniert** werden können; `skills.workshop.approvalPolicy: "pending"` schaltet ein explizites Approval-Gate ein. Seit 2026.8.x läuft auch eine manuelle, neue-zu-alt Rückschau über ältere Sessions (nur SQLite-Cursor-Metadaten). Bewertung für JClaw: reine **Control-Plane**, in Java nachbildbar (Proposal-Datenmodell + Workflow).

### Bewertung für JClaw
**Vollständig in Java machbar (kein JS nötig):** Reiner Text-Injektionsmechanismus.
- Frontmatter: YAML-Parser (SnakeYAML) + JSON5-Fallback.
- Ladeorte/Präzedenz: 1:1 nachbildbar (Workspace-`skills/` + `~/.openclaw/skills`).
- Skill-Auswahl je Agent: in `ClawAgentProperties` abbildbar.
- `metadata.openclaw.install`/`requires`: als Deklaration übernehmen, Install-Scripts laufen separat (siehe Hooks).

---

## 3. Plugins

### Manifest `openclaw.plugin.json`
Pflicht-Datei im Plugin-Root (eigener Standard von OpenClaw).
| Feld | Pflicht | Bedeutung |
|---|---|---|
| `id` | ja | Eindeutige ID (z. B. `openclaw/npm-plugin`). |
| `configSchema` | ja | JSON-Schema zur Plugin-Konfiguration. |
| `contracts` | nein | Deaktivierung: `disable` (z. B. `active-session-contracts`). |
| `activation` | nein | Aktivierungslogik/-Metadaten. |
| `toolMetadata` | nein | Tools-Metadaten (z. B. `autoApproved`-Allowlist). |
| `providers` | nein | Modell-/Media-/Search-/Fetch-/Speech-/Realtime-Provider. |
| `channels` | nein | Channel-Plugins (eigene Manifest-Erweiterung). |
| `setup` | nein | Setup-Befehle. |
| `mcpServers` | nein | Mitgelieferte MCP-Server. |
| `skills` | nein | Mitgelieferte Skills. |

Ergänzend `package.json` mit `openclaw.extensions`, `openclaw.compat` (Ausführung per eingebettetem Agent oder via Codex/Copilot).

### Entry / Laufzeit-API (TypeScript, ESM)
```ts
import { definePluginEntry } from "openclaw/plugin-sdk/plugin-entry";
export default definePluginEntry({
  id: "my-plugin",
  name: "My Plugin",
  register(api) {
    api.registerTool({ name, description, parameters: schema, outputSchema, execute(args) {} });
    api.registerCommand({ name, description, execute(args) {} });
    api.registerTrustedToolPolicy(...);
    api.registerAgentToolResultMiddleware(...);
    api.on("before_tool_call", handler, { matcher, priority, timeoutMs });
  },
});
```
Channel-Plugins nutzen `defineChannelPluginEntry` (aus `openclaw-plugin/channel`).

### Hooks (`api.on`)
Hooks werden sequenziell in absteigender `priority` ausgeführt (jeder kann den Datenfluss modifizieren/blockieren). Katalog:

| Kategorie | Hooks |
|---|---|
| Agent-Turn | `before_model_resolve`, `before_prompt_build`, `before_agent_run`, `before_agent_reply`, `before_agent_finalize`, `agent_end` |
| Tools | `before_tool_call`, `after_tool_call`, `tool_result_persist` |
| Messages | `message_received`, `message_sending`, `message_sent`, `reply_payload_sending` |
| Sessions | `session_start`, `session_end`, `compaction_*` |
| Lifecycle | `gateway_start`, `gateway_stop`, `cron_reconciled`, `cron_changed` |
| Installs | `before_install` |
| Skills | `skill_proposal_evaluate`, `skill_changed` |

> **SDK-Migration (angekündigt in 2026.6.34):** Hook-Stage `before_agent_start`, Root-Imports aus `openclaw/plugin-sdk`, `providerAuthEnvVars` und `channelEnvVars` werden **nach dem 24.07.2026 entfernt**. Migrationsziel: moderne Hook-Stages (siehe Tabelle), fokussierte SDK-Subpath-Imports, Manifest-`setup`-Deskriptoren. Eine 1:1-Parität der Plugin-Laufzeit (P4-01) muss gegen den **neuen** SDK-Stand bauen.

### Kompatible fremde Bundles (Auto-Detect, kein eigenes Schema)
- **Agent Plugins**: `plugin.json` (agent-plugins.org).
- **Codex**: `.codex-plugin/plugin.json`.
- **Claude**: `.claude-plugin/plugin.json`.
- **Cursor**: `.cursor-plugin/plugin.json`.

### Installation
`openclaw plugins install <ClawHub- oder npm-Spec>` (`clawhub:<name>`, npm-Paket, `npm-pack:<tarball>`), `plugins.load.paths` für lokale Pfade.

Seit 2026.8.x verlangen **beliebige ausführbare Plugin-Quellen** eine explizite Bestätigung (`--force`); ClawHub-, npm-, Official-Catalog- und tracked-update-Flüsse bleiben unbehindert, Crestodian-Installationen sind auf vertrauenswürdige Quellen beschränkt.

### Bewertung für JClaw
**1:1 nur über JavaScript/Node möglich** — Plugins sind TypeScript + npm und importieren die `openclaw`-Runtime.
- Manifest-Validierung, `configSchema` und `contracts`/`activation` sind als **Control-Plane in Java nachbildbar** (ohne Codeausführung): Plugin erkennen, Konfig validieren, Zustand verwalten.
- Die **Laufzeit** (Tool-/Hook-/Command-Registrierung) erfordert eine JS-Engine. Dafür kommen in Frage: **Node-Sidecar-Prozess** (empfohlen, siehe §8), GraalJS (eingebettet, aber deutlich höherer Portierungsaufwand), Java-Reimplementation (nur für den OpenClaw-Kern, nicht für Third-Party-Plugins).

---

## 4. Konfiguration `openclaw.json`

### Format
- **JSON5** (Kommentare + Trailing Commas), Default `~/.openclaw/openclaw.json`, Override via `OPENCLAW_CONFIG_PATH`.
- **Strikte Schema-Validierung**: Das Gateway startet bei ungültiger Konfiguration nicht.
- `$include` für Datei-Einbindung, `${VAR}`-Umgebungsvariablen-Substitution, `SecretRefs` für sichere Referenzen.
- Atomisches Replace beim Schreiben; Symlinks vermeiden.

### Struktur
```
gateway.*                  Host/Port, Auth, Logging, Agent-Harness, Control-UI
agents.defaults            Agent-Loop (Loop-Step-Limit, MAX_REPLIES, Autostart, Skills, Tools, Lang)
agents.list / entries      Per-Agent-Override (DM-/Thread-Bindung, Model, Prompts)
channels.*                 Channel-Aktivierung + Config
session.*                  Reset (daily/idle), Thread-Bindings, dmScope
tools.*                    Allow-/Denylisten, Web-Access-Policy, Zeitüberschreitungen
skills.*                   extraDirs, entries
plugins.*                  installation, load.paths
cron.*                     Wiederkehrende Jobs
hooks.*                    Globale Hook-Konfiguration
heartbeat.*, messages.*, models / providers, env
```

### Hot-Reload
Hybrid (Auto-Detect + manuell); RPC `config.get/patch/apply`; laufende Agents behalten ihre Config.

### Bewertung für JClaw
**In Java machbar:** JSON5-Parser (z. B. `org.tomlj` oder eigene Minimallösung) + JSON-Schema-Validierung.
- Die **Teilmenge** (gateway, agents, skills, tools, session, channels, cron) ist sinnvoll abbildbar.
- `$include`/`${VAR}`/`SecretRefs` als generischer Vorverarbeitungsschritt.

---

## 5. Tools

### Kern-Tools (im OpenClaw-Runtime)
Datei-/Shell-Zugriff (`exec`, `read`/`write`/`glob`/`grep`, `apply_patch`), Web (`web_fetch`, `web_search`), Nachrichten/Session, `spawn_agent` u. a.
- Optionale `tools.allow`-Policy (Deny-by-default-Listen pro Agent).
- Web-Zugriff über `webAccess`-Policy (`allowedDomains`).

### MCP
`mcp.servers` + Plugin-`mcpServers`: externe Model Context Protocol-Server werden als Tools integriert.

### Bewertung für JClaw
- JClaw besitzt bereits Tool-Calling via Spring AI `ToolCallback`s (Kern-Tools `CalculatorTool`, `DateTimeTool`). Kern-Tools sind damit **Java-Reimplementationen** — kein JS nötig.
- `exec`/Web-Tools benötigen klare Security-Policies (wie OpenClaw: Allowlisten, Zeitouts, Arbeitsverzeichnis-Beschränkung).
- **Tool-Policies (P1-08, erledigt):** OpenClaws `tools.allow`-Semantik ist als `jclaw.agent.tools.allow`/`.deny` abgebildet (Allow-/Denyliste, Deny-by-Default, Deny schlägt Allow; deaktivierte Tools erscheinen nicht im Tool-Schema des Modells). `toolMetadata.autoApproved` ist mangels Approval-Flows noch ohne Paritätsbezug (relevant erst mit Human-in-the-Loop/Gateway-Auth, P2-06).
- **MCP-Client**: eigenständig in Java integrierbar (kein Plugin-Problem); mit `mcp.servers`-Abschnitt der Konfig kompatibel machen.

---

## 6. Memory / Sessions

- **Session** = Konversations-Kontext (DM-/Thread-Bindung, `session.reset: daily|idle`, `dmScope`), Persistenz in SQLite.
- **Session-first (2026.7.1):** Sessions sind die primäre Navigationseinheit der Control-UI — durchsuchbare Liste, **Session-Gruppen**, **generierte Titel**, **Transcript-Export**, Kontext-Verbrauch und Modell-/Thinking-Steuerung je Session.
- **Konversationell**: jüngste Nachrichten + vorherige Loop-Steps.
- **Wissen/Embeddings**: langfristiges semantisches Memory (Memory-Plugin, `kind: "memory"`), chunks werden bei Injektion ausgewertet.
- **Compaction**: Kontext wird komprimiert, wenn Session-Grenzen erreicht werden.

### Bewertung für JClaw
- JClaw hat bereits H2-JDBC-`ChatMemory` (`chat_message`-Tabelle). Auf das **Session-Konzept** erweitern (Reset-Strategien, Thread-Bindings) ist reine Java-Arbeit.
- Embeddings/Vektorsuche (z. B. pgvector, Chroma) später als eigenständiger Baustein — kein Plugin-Format betroffen.

---

## 7. Anforderungen an die Kompatibilitätsschicht (Zusammenfassung)

| Fähigkeit | OpenClaw-Format | In Java machbar? | Aufwand |
|---|---|---|---|
| Skills laden/parsen | `SKILL.md` + YAML/JSON5-Frontmatter | **Ja** (SnakeYAML + Fallback) | Gering |
| Skill-Injektion in Prompt | Text-Kontext + Allowlisten | **Ja** | Gering |
| Skill Workshop | `skills.workshop.*` (Proposals, Approval) | **Ja** (Control-Plane) | Gering |
| Konfig `openclaw.json` | JSON5 + strikte Schema-Validierung | **Ja** (JSON5-Parser + Validator) | Mittel |
| Session-/Memory-Konzept | SQLite, Reset, Thread-Bindings, Compaction | **Ja** (auf H2 aufbauend) | Mittel |
| Kern-Tools | Kern-Tools + Policies | **Ja** (Spring AI `ToolCallback`, Security-Policies) | Mittel |
| MCP-Server anbinden | `mcp.servers` | **Ja** (MCP-Client/SDK in Java) | Mittel |
| Interne Hooks (`HOOK.md`-Scripts, `/new`, `/reset`, `gateway:startup`) | Shell-/Node-Scripts | **Ja** (Script-Runner + Lifecycle-Events) | Mittel |
| Plugin-Manifest lesen/validieren | `openclaw.plugin.json` (+ fremde Bundles) | **Ja** (Control-Plane ohne Code) | Mittel |
| **Plugin-Laufzeit** (Tools/Hooks/Commands/Channels) | TypeScript + `openclaw`-Runtime | **Nein — nur über JS/Node** | **Hoch** |

---

## 8. Empfohlene Architektur

**Entschieden (ADR-0001): Java-Kern + Node-Sidecar** (schwache Kopplung, getrennte Prozesse). Framing: JSON-RPC 2.0, Newline-delimited über stdio.

```
┌─────────────────────────── Java (JClaw) ───────────────────────────┐
│  Config (JSON5 + Schema) · Skill-Loader · Agent-Loop · Kern-Tools   │
│  Session/Memory (H2) · HTTP/REST-API · Gateway-Äquivalent           │
│                                                                     │
│  Plugin-Control-Plane: Manifests lesen, configSchema validieren,    │
│  contracts/activation bewerten (ohne Codeausführung)                │
└──────────────▲──────────────────────────────▲───────────────────────┘
        Bridge/JSON-RPC                Bridge/JSON-RPC
               │                                │
┌──────────────┴─────────────────────┐   ┌──────┴──────────────────────┐
│  Node-Sidecar (nur für Plugins)    │   │  externe Dienste            │
│  lädt openclaw.plugin.json/TS      │   │  MCP-Server · Ollama · …    │
│  execute → Ergebnis an Java        │   └─────────────────────────────┘
└────────────────────────────────────┘
```

- **Sidecar** führt nur die Plugin-Laufzeit aus (`definePluginEntry`/`defineChannelPluginEntry`); Java ruft Tools/Hooks über eine schlanke JSON-RPC/stdio-Bridge auf.
- Skills, Konfig, Kern-Tools, Memory laufen **ohne Node** in Java → reines OpenClaw-Setup benötigt keinen Sidecar.
- Voraussetzung auf Zielsystemen: Node.js-Runtime (dokumentieren, wie OpenClaw es tut).
- Validierung: Bridge-Protokoll (P1-03) vollständig spezifiziert und als verwaltbarer Dienst umgesetzt — Framing, Methoden-Katalog, Fehlercodes, Timeouts, Restart. Siehe [bridge-protocol.md](bridge-protocol.md) und ADR-0001.

---

## 9. Nächste Schritte

> Der Fortschritt wird in der [Paritäts-Roadmap](parity-roadmap.md) verfolgt. Stand: P0 (Fundament), P1-01 (Plugin Control-Plane), P1-02 (Architektur-Entscheidung Node-Sidecar), **P1-03 (Bridge-Protokoll)**, **P1-04 (MCP-Client)**, **P1-05 (Web-Tools)**, **P1-06 (`apply_patch`)**, **P1-07 (`spawn_agent`)**, **P1-08 (Tool-Policies)** und **P1-09 (Session-Konzept)** sind abgeschlossen. **Phase 1 ist damit komplett.**

1. Danach: Konfig-Gateway (JSON5, Hot-Reload, P2-01/P2-02/P2-04), Hooks (P1-11), Channels gemäß Roadmap.
