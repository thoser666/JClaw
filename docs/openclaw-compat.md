# OpenClaw-Kompatibilität — Analyse der Formate und Architektur

Stand: 2026-08-08 · Quellen: `github.com/openclaw/openclaw` (`/docs`), `docs.openclaw.ai` (Plugins, Manifest, Hooks, Gateway/Configuration), AgentSkills-Spec (`agentskills.io`), Agent-Plugins-Standard (`agent-plugins.org`).

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

### Kompatible fremde Bundles (Auto-Detect, kein eigenes Schema)
- **Agent Plugins**: `plugin.json` (agent-plugins.org).
- **Codex**: `.codex-plugin/plugin.json`.
- **Claude**: `.claude-plugin/plugin.json`.
- **Cursor**: `.cursor-plugin/plugin.json`.

### Installation
`openclaw plugins install <ClawHub- oder npm-Spec>` (`clawhub:<name>`, npm-Paket, `npm-pack:<tarball>`), `plugins.load.paths` für lokale Pfade.

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
- **MCP-Client**: eigenständig in Java integrierbar (kein Plugin-Problem); mit `mcp.servers`-Abschnitt der Konfig kompatibel machen.

---

## 6. Memory / Sessions

- **Session** = Konversations-Kontext (DM-/Thread-Bindung, `session.reset: daily|idle`, `dmScope`), Persistenz in SQLite.
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
| Konfig `openclaw.json` | JSON5 + strikte Schema-Validierung | **Ja** (JSON5-Parser + Validator) | Mittel |
| Session-/Memory-Konzept | SQLite, Reset, Thread-Bindings, Compaction | **Ja** (auf H2 aufbauend) | Mittel |
| Kern-Tools | Kern-Tools + Policies | **Ja** (Spring AI `ToolCallback`, Security-Policies) | Mittel |
| MCP-Server anbinden | `mcp.servers` | **Ja** (MCP-Client/SDK in Java) | Mittel |
| Interne Hooks (`HOOK.md`-Scripts, `/new`, `/reset`, `gateway:startup`) | Shell-/Node-Scripts | **Ja** (Script-Runner + Lifecycle-Events) | Mittel |
| Plugin-Manifest lesen/validieren | `openclaw.plugin.json` (+ fremde Bundles) | **Ja** (Control-Plane ohne Code) | Mittel |
| **Plugin-Laufzeit** (Tools/Hooks/Commands/Channels) | TypeScript + `openclaw`-Runtime | **Nein — nur über JS/Node** | **Hoch** |

---

## 8. Empfohlene Architektur

**Java-Kern + Node-Sidecar** (schwache Kopplung, getrennte Prozesse):

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

- **Sidecar** führt nur die Plugin-Laufzeit aus (`definePluginEntry`/`defineChannelPluginEntry`); Java ruft Tools/Hooks über eine schlanke JSON-RPC/HTTP-Bridge auf.
- Skills, Konfig, Kern-Tools, Memory laufen **ohne Node** in Java → reines OpenClaw-Setup benötigt keinen Sidecar.
- Voraussetzung auf Zielsystemen: Node.js-Runtime (dokumentieren, wie OpenClaw es tut).

---

## 9. Nächste Schritte

1. **Pilot-Skill**: Einen OpenClaw-Skill (z. B. aus `~/.agents/skills`) in JClaw laden und per Agent verwenden — validiert §2 und den Skill-Pipeline-Pfad.
2. **Pilot-Plugin-Manifest**: Ein echtes `openclaw.plugin.json` parsen/validieren (Control-Plane) ohne Codeausführung.
3. **Architektur-Entscheidung bestätigen**: Node-Sidecar (Empfehlung) vs. GraalJS vs. Java-Reimplementation auf Basis dieses Dokuments.
4. Danach: Bridge-Protokoll spezifizieren, Kern-Tools erweitern, Session-Konzept auf H2 umbauen, MCP-Client anbinden.
