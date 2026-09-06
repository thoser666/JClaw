# JClaw

JClaw ist ein autonomer, hochgradig strukturierter Software-Agent. Das Projekt ist ein moderner Java- und Spring-basierter Port von OpenClaw, optimiert für lokale LLM-Infrastrukturen via Ollama.

Die Anwendung ist strikt nach den Prinzipien der **Hexagonalen Architektur** (Ports and Adapters) aufgebaut, um die Kern-Domainlogik vollständig von Infrastruktur-Entscheidungen (wie dem spezifischen KI-Provider oder Web-Frameworks) zu entkoppeln.

Der Weg zur **100 %-Parität mit OpenClaw** ist in der [Paritäts-Roadmap](docs/parity-roadmap.md) dokumentiert; die Formatanalyse dazu liegt in [docs/openclaw-compat.md](docs/openclaw-compat.md), getroffene Architektur-Entscheidungen in `docs/adr/` (z. B. [ADR-0001 Node-Sidecar](docs/adr/0001-node-sidecar-plugin-runtime.md)) und die JSON-RPC-Spezifikation für den Sidecar in [docs/bridge-protocol.md](docs/bridge-protocol.md).

## Tech Stack

* **Java 25** (GraalVM Community)
* **Spring Boot 4.1.0**
* **Spring AI 2.0.0**
* **Ollama** (Default Model: `qwen3:8b`)
* **H2** (Datei-basierte Datenbank für das Konversations-Memory)
* **Maven**

## Architektur-Überblick

Das Projekt folgt der hexagonalen Struktur unter dem Package-Stamm `biz.brumm`:

* `domain.model`: Reine Fachobjekte (`AgentCommand`, `AgentResponse`, `ToolInvocation`), frei von Framework-Abhängigkeiten.
* `domain.port.in` / `out`: Schnittstellen für eingehende Befehle (Use Cases, z. B. `ExecuteTaskUseCase`) und ausgehende Infrastruktur (`AiProviderPort`, `AgentTool`).
* `domain.service`: Die Kern-Logik des Agenten (`ClawAgentService`), die die Ports orchestriert.
* `infrastructure.adapter`: Die technische Implementierung der Ports (REST-Controller für den Inbound-Verkehr, `OllamaAiAdapter` und Tools für den Outbound-Verkehr).
* `config`: Konfigurationsklassen für Agent-Eigenschaften und Chat-Memory (`ChatMemoryConfig`).

## Funktionen

* **Agent-Loop mit Tool-Nutzung:** Das LLM kann in mehreren Iterationen Werkzeuge aufrufen und deren Ergebnisse verarbeiten, bis eine finale Antwort vorliegt oder das Iterationslimit erreicht ist.
  * `CalculatorTool` (`calculate`) – sicherer Rechner mit eigenem, minimalem Ausdrucks-Parser.
  * `DateTimeTool` (`getCurrentDateTime`) – aktuelle Uhrzeit/Datum mit optionaler Zeitzone.
  * `FileTool` (`readFile`, `listDirectory`, `writeFile`, `glob`, `grep`) und `apply_patch` – Dateizugriff, -suche und -änderung innerhalb eines konfigurierten Arbeitsverzeichnisses (optional, s. u.).
  * `ShellTool` (`runCommand`) – führt Shell-Befehle im Arbeitsverzeichnis aus (optional, s. u.).
  * `SpawnAgentTool` (`spawn_agent`) – startet einen Sub-Agenten mit eigenem Prompt und gleichen Werkzeugen (optional, s. u.).
* **Skills (OpenClaw/AgentSkills-Format):** Skills aus dem konfigurierten Verzeichnis (`SKILL.md` mit YAML-Frontmatter) werden in den System-Prompt injiziert, sobald sie per `jclaw.agent.skills.enabled` aktiviert sind.
* **Konversations-Memory:** Über eine optionale `contextId` wird der Gesprächsverlauf (Message-Window mit begrenzter Nachrichtenanzahl) pro Kontext gespeichert und bei Folgeanfragen wieder eingespielt. Die Nachrichten werden persistent in einer H2-Datei-Datenbank abgelegt (`./data/jclaw.mv.db`) und überleben so App-Neustarts.
* **Open Memory Vault (P4-02):** Materialisiert Konversations-Memory als menschenlesbare Markdown-Dokumente in einem konfigurierbaren Verzeichnis — lesbar/editierbar z. B. via Tolaria oder Obsidian. H2 bleibt Quelle der Wahrheit; der Vault ist ein idempotenter Auszug, der Compaction/Neustarts übersteht. Ein Watcher-Sync (Read-Back) erkennt User-Änderungen an `.md`-Dateien und ingestet sie zurück in die Konversation (siehe [Open Memory Vault](#open-memory-vault-p4-02)).
* **Plugins (Control-Plane):** Plugin-Manifeste im OpenClaw-Format (`openclaw.plugin.json`) sowie kompatible fremde Bundles (Agent Plugins, Codex, Claude, Cursor) werden gelesen und ohne Codeausführung validiert (Pflichtfelder, Schema-Struktur).
* **Node-Sidecar-Bridge (P1-03):** Verwaltete JSON-RPC-Bridge zu einem Node.js-Sidecar-Prozess (Handshake, Call-/Ready-Timeout, strukturierte Fehler, Restart) — Grundlage für die Plugin-Laufzeit in P4-01. Spezifikation: [docs/bridge-protocol.md](docs/bridge-protocol.md).
* **Control-UI (P2-05):** Statische Web-Oberfläche (kein Build-Schritt, keine externen Abhängigkeiten) unter `http://localhost:8080` — Agent-Aufgaben ausführen, Konversationen laden/löschen, Skills und Plugins anzeigen (siehe [Control-UI](#control-ui)).
* **Fehlerbehandlung:** Ein globaler `@RestControllerAdvice` liefert bei ungültigen Anfragen (z. B. leerem Prompt) eine 400-Antwort mit Fehlermeldung.
* **Tool-Policies (P1-08):** Per `jclaw.agent.tools.allow`/`.deny` lassen sich einzelne Werkzeuge für den Agenten freischalten bzw. sperren (Allow-/Denyliste, Deny-by-Default; Deny schlägt Allow). Deaktivierte Werkzeuge werden dem LLM nicht als Tool-Schema angeboten — analog zu OpenClaws `tools.allow`-Policy.
* **Session-Konzept (P1-09):** Das ursprüngliche `contextId`-Memory wurde durch ein Session-Modell erweitert. Jeder Aufruf einer `contextId` löst automatisch eine Session auf (oder erstellt eine neue). Sessions haben einen generierten Titel (aus der ersten Nachricht, max. 60 Zeichen), Zeitstempel und unterstützen Reset-Strategien (`daily`, `idle`, `none`) über `jclaw.session.*`. Session-Metadaten werden persistent in einer H2-`session`-Tabelle gespeichert. Die Control-UI zeigt Sessions als eigene Navigation an mit Auswahl- und Löschfunktion.
* **Spawn-Agent (P1-07):** Der Agent kann Sub-Agenten über `spawn_agent` starten, die eine Aufgabe eigenständig mit denselben Werkzeugen bearbeiten. Rekursionstiefe ist konfigurierbar begrenzt (Deny-by-Default).

## Voraussetzungen

1. **Ollama** muss lokal laufen.
2. Das gewünschte Modell muss heruntergeladen sein:
   ```bash
   ollama pull qwen3:8b
   ```

## Docker

Das Multi-Stage-`Dockerfile` (JDK 25 zum Bauen, schlankes JRE-25-Image zur Laufzeit) erzeugt das Anwendungs-Image selbstständig:

```bash
docker build -t jclaw .
docker run --rm -p 8080:8080 jclaw
```

* Das JAR wird **versionsagnostisch** über ein Wildcard kopiert (`target/jclaw-*.jar`) — ein Versionsbump in `pom.xml` bricht den Docker-Build daher nicht.
* Über GitHub Actions (`docker-image.yml`) wird das Image bei jedem Push auf `master`/`develop` (und für `v*`-Tags) gebaut und nach `ghcr.io/<owner>/jclaw` gepusht. Lokal geprüft wird das von `DockerfileTest`.

## Konfiguration

Einstellungen in `src/main/resources/application.properties` oder in `openclaw.json` (JSON5-Format, P2-01):

Die JSON5-Datei wird beim Start automatisch geladen und überschreibt Werte aus `application.properties`. Sie verwendet OpenClaw-kompatible Kurzschlüssel (z. B. `agents.max-iterations`), die automatisch auf Spring-Boot-Property-Namen gemappt werden.

| Property | Default | Beschreibung |
|---|---|---|
| `spring.ai.ollama.base-url` | `http://localhost:11434` | Ollama-Basis-URL |
| `spring.ai.ollama.chat.options.model` | `qwen3:8b` | LLM-Modell |
| `spring.ai.ollama.chat.options.temperature` | `0.3` | Sampling-Temperatur |
| `spring.ai.ollama.chat.options.num-ctx` | `8192` | Kontextfenster (Tokens) |
| `server.port` | `8080` | HTTP-Port |
| `spring.datasource.url` | `jdbc:h2:file:./data/jclaw` | JDBC-URL der Memory-Datenbank (Datei-basiert) |
| `spring.datasource.driver-class-name` | `org.h2.Driver` | JDBC-Treiber |
| `spring.sql.init.mode` | `always` | Führt `schema.sql` bei jedem Start aus (`CREATE TABLE IF NOT EXISTS`) |
| `jclaw.agent.max-iterations` | `8` | Maximale Agent-Iterationen (Tool-Runden) |
| `jclaw.agent.max-history-messages` | `10` | Nachrichten pro Kontext im Memory-Fenster |
| `jclaw.agent.plugins.dir` | `./plugins` | Verzeichnis mit Plugin-Ordnern (Manifeste) |
| `jclaw.agent.skills.dir` | `./skills` | Verzeichnis mit Skill-Ordnern (`SKILL.md`) |
| `jclaw.agent.skills.enabled` | `-` (leer) | Namen der zu ladenden Skills (leer = keine Skills aktiv) |
| `jclaw.agent.filetool.workdir` | `-` (nicht gesetzt) | Arbeitsverzeichnis der Datei-Werkzeuge. Erst wenn gesetzt, werden `readFile`, `listDirectory`, `writeFile`, `glob`, `grep` und `apply_patch` registriert (Deny-by-Default) |
| `jclaw.agent.filetool.max-read-bytes` | `1048576` (1 MiB) | Maximale Dateigröße, die der Agent lesen darf |
| `jclaw.agent.shelltool.enabled` | `false` | Schaltet das `runCommand`-Werkzeug frei (nur `true` registriert es, Deny-by-Default) |
| `jclaw.agent.shelltool.workdir` | aktuelles Verzeichnis | Arbeitsverzeichnis, in dem Befehle ausgeführt werden |
| `jclaw.agent.shelltool.timeout-seconds` | `30` | Maximale Laufzeit eines Befehls |
| `jclaw.agent.shelltool.max-output-chars` | `10000` | Maximale Zeichenanzahl der zurückgegebenen Ausgabe |
| `jclaw.mcp.enabled` | `false` | Schaltet die MCP-Integration frei (nur `true` verbindet Server, Deny-by-Default) |
| `jclaw.mcp.request-timeout` | `60s` | Timeout für Anfragen an MCP-Server |
| `jclaw.mcp.servers.<name>.url` | `-` | Basis-URL eines HTTP-MCP-Servers (Endpunkt `/mcp` wird angehängt) |
| `jclaw.mcp.servers.<name>.endpoint` | `/mcp` | MCP-Endpunkt-Pfad für HTTP-Server |
| `jclaw.mcp.servers.<name>.command` | `-` | Befehl eines STDIO-MCP-Servers |
| `jclaw.mcp.servers.<name>.args` | `-` | Argumente des STDIO-Befehls |
| `jclaw.mcp.servers.<name>.env` | `-` | Umgebungsvariablen des STDIO-Befehls |
| `jclaw.agent.webtool.enabled` | `false` | Schaltet die Web-Werkzeuge frei (nur `true` registriert sie, Deny-by-Default) |
| `jclaw.agent.webtool.allowed-domains` | `-` (leer) | Erlaubte Domains für `web_fetch` (inkl. Subdomains; leer = kein Abruf erlaubt) |
| `jclaw.agent.webtool.search-endpoint` | `https://api.duckduckgo.com` | Basis-URL des Such-Endpoints für `web_search` |
| `jclaw.agent.webtool.fetch-timeout-seconds` | `10` | Timeout für Abrufe und Suchen |
| `jclaw.agent.webtool.max-fetch-bytes` | `200000` | Maximale Größe einer abgerufenen Antwort |
| `jclaw.agent.webtool.max-search-results` | `5` | Maximale Anzahl von Treffern pro Suche |
| `jclaw.agent.tools.allow` | `-` (leer) | Allowliste der Tool-Namen (`readFile`, `runCommand`, `web_fetch`, MCP-Tools wie `math-server_add`, …). Leer = alle Tools erlaubt; gesetzt = nur die genannten Tools aktiv (Deny-by-Default) |
| `jclaw.agent.tools.deny` | `-` (leer) | Denyliste der Tool-Namen. Leer = kein Tool gesperrt; **Deny schlägt Allow** |
| `jclaw.agent.spawnagent.enabled` | `false` | Schaltet das `spawn_agent`-Werkzeug frei (Deny-by-Default) |
| `jclaw.agent.spawnagent.max-depth` | `3` | Maximale Verschachtelungstiefe für Sub-Agenten (0 = unbegrenzt) |
| `jclaw.session.reset-mode` | `none` | Session-Reset-Strategie: `none`, `daily` (reset pro Tag) oder `idle` (reset nach Inaktivität) |
| `jclaw.session.reset-at-hour` | `4` | Stunde (0–23) für den `daily`-Reset (lokal) |
| `jclaw.session.reset-idle-minutes` | `60` | Inaktivitätszeit in Minuten für den `idle`-Reset |
| `jclaw.config.hot-reload.enabled` | `false` | Auto-Reload der JSON5-Konfiguration bei Dateiänderungen (WatchService) |
| `jclaw.hooks.enabled` | `false` | Lifecycle-Hooks via `HOOK.md`-Scripts (Deny-by-Default) |
| `jclaw.hooks.dir` | `./hooks` | Verzeichnis mit Hook-Ordnern (`HOOK.md`) |
| `jclaw.hooks.script-timeout` | `30` | Timeout für Script-Ausführungen in Sekunden |
| `jclaw.compaction.enabled` | `false` | LLM-basierte Kontext-Kompression (Deny-by-Default) |
| `jclaw.compaction.threshold` | `20` | Mindestanzahl Nachrichten vor Compaction |
| `jclaw.compaction.retain-count` | `4` | Jüngste Nachrichten, die nie komprimiert werden |
| `jclaw.memory.vault.enabled` | `false` | Open Memory Vault aktivieren (Deny-by-Default) |
| `jclaw.memory.vault.dir` | `./vault` | Verzeichnis für Markdown-Memory-Dokumente (menschenlesbar/-editierbar, z. B. via Tolaria/Obsidian) |
| `jclaw.cron.enabled` | `false` | Cron-Job-System aktivieren (Deny-by-Default) |
| `jclaw.cron.interval` | `60` | Intervall in Sekunden für Job-Prüfung |
| `jclaw.cron.max-retries` | `3` | Maximale Wiederholungen bei Fehler |
| `jclaw.channels.enabled` | `false` | Channel-System aktivieren (Deny-by-Default) |
| `jclaw.channels.default-timeout` | `30` | Standard-Timeout für Channel-Adapter in Sekunden |
| `jclaw.auth.enabled` | `false` | API-Token-Authentifizierung für alle `/api/**`-Endpunkte |
| `jclaw.auth.public-paths` | `-` (leer) | Öffentliche Pfade ohne Auth (z. B. `/api/v1/gateway/status`) |

## JSON5-Konfiguration (P2-01/P2-02)

Zusätzlich zu `application.properties` kann eine `openclaw.json`-Datei im Projektstamm verwendet werden (OpenClaw-kompatibel):

```json5
{
  // Kommentare sind erlaubt
  "agents": {
    "max-iterations": 8,
    "spawnagent": { "enabled": false, "max-depth": 3 }
  },
  "session": {
    "reset-mode": "daily",   // none | daily | idle
    "reset-at-hour": 4
  },
  "mcp": {
    "enabled": false
  }
}
```

* Die JSON5-Datei wird beim Start automatisch als `EnvironmentPostProcessor` geladen.
* **Kurzschlüssel** werden automatisch auf Spring-Boot-Property-Namen gemappt (z. B. `agents.max-iterations` → `jclaw.agent.max-iterations`).
* **`${VAR}`-Substitution**: Werte können Umgebungsvariablen oder interne Referenzen enthalten.
* **`$include`**: Dateien können per `$include: "base.json5"` eingebunden werden (relative Pfade).
* **Strikte Validierung** (P2-02): Bei unbekannten Top-Level-Bereichen, ungültigen Session-Reset-Modi oder fehlerhaften Werten wird der Start verhindert.

### Hot-Reload (P2-03)

Die JSON5-Konfiguration kann zur Laufzeit neu geladen werden:

* **Manuell:** `POST /api/v1/config.apply` — Liest die `openclaw.json` neu, validiert und aktualisiert die Spring-Environment.
* **Automatisch:** Setze `jclaw.config.hot-reload.enabled=true` — `openclaw.json` wird über `WatchService` überwacht; Änderungen werden mit 500 ms Debounce automatisch geladen.

Laufende Agents behalten ihre Konfiguration — nur neue Aufrufe verwenden die aktualisierten Werte.

## Skills

JClaw lädt Skills im **AgentSkills-Format** (OpenClaw-kompatibel): Jedes Unterverzeichnis von `jclaw.agent.skills.dir` entspricht einem Skill und enthält eine `SKILL.md`-Datei mit YAML-Frontmatter:

```markdown
---
name: code-review
description: Prüft Pull Requests systematisch auf Bugs.
---

Prüfe Änderungen auf Fehler ...
```

* Pflichtkonzept: `name` (Fallback: Ordnername) und `description`.
* Nur per `jclaw.agent.skills.enabled` aktivierte Skills werden in den System-Prompt aufgenommen (Deny-by-Default).
* Unterverzeichnisse ohne `SKILL.md`/`skill.md` oder ohne gültiges Frontmatter werden übersprungen.

## Plugins (Control-Plane)

JClaw liest Plugin-Manifeste aus `jclaw.agent.plugins.dir` (Standard: `./plugins`). Jedes Unterverzeichnis entspricht einem Plugin; erkannt werden:

| Format | Manifest-Datei | Pflichtfelder |
|---|---|---|
| OpenClaw | `openclaw.plugin.json` | `id` (Struktur-Check von `configSchema`/`mcpServers`) |
| Agent Plugins | `plugin.json` | `name`, `version` |
| Codex | `.codex-plugin/plugin.json` | `name`, `version` |
| Claude | `.claude-plugin/plugin.json` | `name`, `version` |
| Cursor | `.cursor-plugin/plugin.json` | `name`, `version` |

Beispiel (OpenClaw):

```json
{
  "id": "acme/demo",
  "name": "Demo Plugin",
  "configSchema": { "type": "object" }
}
```

Die Manifeste werden **ohne Codeausführung** validiert (Control-Plane). Ungültige Plugins werden in der API mit `valid: false` und einer Fehlermeldung ausgewiesen statt verworfen. Die eigentliche Plugin-Laufzeit (TypeScript) erfordert einen Node-Sidecar (siehe `docs/openclaw-compat.md`).

## Datei-Werkzeuge

Sobald `jclaw.agent.filetool.workdir` gesetzt ist, erhält der Agent die Werkzeuge `readFile`, `listDirectory`, `writeFile`, `glob`, `grep` und `apply_patch`:

* Alle Pfade werden relativ zum Arbeitsverzeichnis aufgelöst.
* Zugriffe außerhalb des Arbeitsverzeichnisses (Pfad-Traversal, absolute Pfade, `..`) werden mit einer Fehlermeldung abgewiesen.
* `readFile` verweigert Dateien, die größer als `jclaw.agent.filetool.max-read-bytes` sind (Standard: 1 MiB).
* `writeFile` legt fehlende Zwischenverzeichnisse automatisch an.
* `glob` findet Dateien per Glob-Muster (z. B. `**/*.java`, `src/**/*.txt`) und gibt die Treffer relativ zum Arbeitsverzeichnis zurück (max. 100 Einträge).
* `grep` durchsucht Textdateien (rekursiv in einem Verzeichnis oder eine einzelne Datei) nach einem regulären Ausdruck und liefert `pfad:zeile: inhalt`-Treffer (max. 200).

Ohne gesetztes `workdir` sind die Datei-Werkzeuge nicht aktiv (Deny-by-Default).

### `apply_patch`

`apply_patch` wendet einen Patch im apply_patch-Format (OpenClaw-/Claude-kompatibel) auf Dateien im Arbeitsverzeichnis an. Ein Patch besteht aus Blöcken für Anlegen, Ändern und Löschen:

```text
*** Begin Patch
*** Add File: notizen.txt
Zeile eins
Zeile zwei

*** Update File: src/Main.java
@@
  public static void main(String[] args) {
-    System.out.println("alt");
+    System.out.println("neu");
  }
*** Delete File: veraltet.txt
*** End Patch
```

* `*** Add File: <pfad>` legt eine neue Datei an (fehlende Zwischenverzeichnisse werden erzeugt); der Inhalt reicht bis zum nächsten `***`-Block.
* `*** Update File: <pfad>` ändert eine bestehende Datei über `@@`-Hunks: Zeilen mit Leerzeichen-Präfix sind Kontext (muss im Dateistand vorkommen), `-` entfernt eine Zeile, `+` fügt eine Zeile ein. Mehrere Hunks pro Datei sind erlaubt.
* `*** Delete File: <pfad>` löscht eine Datei.
* Der Patch wird **vollständig geparst und geprüft, bevor geschrieben wird** (Alles-oder-nichts): Schlägt z. B. ein Hunk fehl, bleibt der gesamte Dateistand unverändert.

## Shell-Werkzeug

Sobald `jclaw.agent.shelltool.enabled=true` gesetzt ist, erhält der Agent das Werkzeug `runCommand`:

* Befehle laufen im konfigurierten `workdir` (Standard: aktuelles Verzeichnis).
* Ein Befehl wird nach `jclaw.agent.shelltool.timeout-seconds` Sekunden abgebrochen (inkl. Kindprozessen).
* Die Ausgabe wird auf `jclaw.agent.shelltool.max-output-chars` Zeichen gekürzt; auch Exit-Codes werden gemeldet.

**Sicherheitshinweis:** Shell-Ausführung ist mächtig und riskant. Standardmäßig ist das Werkzeug deaktiviert — aktivieren Sie es nur in kontrollierten Umgebungen.

## Spawn-Agent (Multi-Agent)

Sobald `jclaw.agent.spawnagent.enabled=true` gesetzt ist, erhält der Agent das Werkzeug `spawn_agent`:

```properties
jclaw.agent.spawnagent.enabled=true
jclaw.agent.spawnagent.max-depth=3
```

* Der Agent kann mit `spawn_agent` einen Sub-Agenten starten, der eine Aufgabe eigenständig bearbeitet.
* Der Sub-Agent erhält dieselben Werkzeuge wie der Haupt-Agent und führt den Agent-Loop mit eigener System-Prompt aus.
* Optional kann eine `contextId` übergeben werden, um den Konversationskontext zu teilen; ohne Angabe wird ein frischer Kontext verwendet.
* Die Rekursionstiefe ist durch `max-depth` begrenzt (Standard: 3), um endlose Verschachtelung zu verhindern.
* Bei Erreichen des Limits oder bei Fehlern gibt das Werkzeug eine Fehlermeldung zurück.

**Sicherheitshinweis:** `spawn_agent` ist mächtig und kann unerwartete Kosten verursachen. Standardmäßig ist das Werkzeug deaktiviert — aktivieren Sie es nur in kontrollierten Umgebungen.

## MCP-Server

Sobald `jclaw.mcp.enabled=true` gesetzt ist, verbindet sich JClaw mit den konfigurierten MCP-Servern (Model Context Protocol) und registriert deren Tools beim Agenten:

```properties
jclaw.mcp.enabled=true
jclaw.mcp.servers.filesystem.url=http://localhost:8080
jclaw.mcp.servers.linter.command=npx
jclaw.mcp.servers.linter.args=-y, @modelcontextprotocol/server-everything
```

* **HTTP-Transport:** `url` ist die Basis-URL ohne Pfad; der MCP-Endpunkt (Standard `/mcp`) wird automatisch angehängt und kann per `endpoint` überschrieben werden.
* **STDIO-Transport:** `command` startet ein ausführbares Skript; `args` und `env` sind optional.
* Pro Server muss genau eines von `url` oder `command` gesetzt sein.
* Die Modell-seitigen Tool-Namen werden mit dem Server-Namen präfigiert (`<server>_<tool>`); Zeichen außerhalb von `[a-zA-Z0-9_-]` werden durch `_` ersetzt.
* Scheitert eine Verbindung, startet die Anwendung mit einer Fehlermeldung (Fail-Fast).

## Web-Werkzeuge

Sobald `jclaw.agent.webtool.enabled=true` gesetzt ist, erhält der Agent die Werkzeuge `web_fetch` und `web_search`:

```properties
jclaw.agent.webtool.enabled=true
jclaw.agent.webtool.allowed-domains=example.com, docs.spring.io
jclaw.agent.webtool.max-search-results=5
```

* **`web_fetch`** lädt den Inhalt einer Webseite und liefert ihn als Text (HTML wird bereinigt, `<script>`/`<style>` entfernt). Aus Sicherheitsgründen sind nur `http`/`https`-URLs erlaubt, deren Host exakt in `jclaw.agent.webtool.allowed-domains` liegt oder eine Subdomain davon ist (`docs.example.com` zählt zu `example.com`). Eine leere Liste blockiert alle Abrufe (Deny-by-Default).
* **`web_search`** fragt den konfigurierten `jclaw.agent.webtool.search-endpoint` an (Standard: DuckDuckGo Instant Answer API) und liefert Treffer mit Beschreibung und URL. Der Endpoint ist frei ersetzbar.
* Große Antworten werden auf `jclaw.agent.webtool.max-fetch-bytes` begrenzt; Abrufe und Suchen brechen nach `jclaw.agent.webtool.fetch-timeout-seconds` ab.

**Sicherheitshinweis:** `web_fetch` kann interne Adressen ansprechen (SSRF-Gefahr). Aktivieren Sie nur vertrauenswürdige Domains.

## Persistenz

Das Konversations-Memory wird über Spring JDBC in einer eingebetteten **H2-Datenbank** gespeichert:

* Das Tabellenschema ist in `src/main/resources/schema.sql` definiert (Tabellen `chat_message`, `session`, `channel`, `channel_binding`, `channel_message`).
* Die Datenbankdatei wird beim ersten Start automatisch unter `./data/jclaw.mv.db` angelegt (Ordner `data/` ist in `.gitignore` ausgenommen).
* `JdbcChatMemoryRepository` serialisiert alle Spring-AI-Message-Typen (System, User, Assistant inkl. Tool-Calls, Tool-Response) als JSON in die Spalte `message_json`.
* Ein Neustart der Anwendung stellt den Gesprächsverlauf einer `contextId` automatisch wieder her.

Hinweis: Die H2-Datei-Datenbank dient als lokale Standard-Persistenz. Für andere Datenbanken muss lediglich die `spring.datasource.url` (und ggf. der Treiber) angepasst werden; das Schema wird über `schema.sql` verwaltet.

## Start

```bash
mvnw spring-boot:run
```

## API

### Agent-Aufgabe ausführen

`POST /api/v1/tasks`

Request-Body:

```json
{
  "prompt": "Berechne (12+4)*2/8",
  "contextId": null
}
```

* `prompt` (Pflicht): Die Aufgabe für den Agenten.
* `contextId` (optional): Aktiviert das Konversations-Memory. Identische `contextId`-Werte teilen sich denselben Gesprächsverlauf.

Antwort (HTTP 200):

```json
{
  "content": "4",
  "timestamp": "2026-08-06T07:09:26.368837900Z",
  "toolInvocations": [
    {
      "name": "calculate",
      "arguments": "{\"expression\":\"(12 + 4) * 2 / 8\"}",
      "result": "4"
    }
  ],
  "iterations": 2,
  "sessionId": "s1"
}
```

Beispiel mit `curl`:

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Berechne (12+4)*2/8","contextId":null}'
```

### Verfügbare Skills auflisten

`GET /api/v1/skills`

Antwort (HTTP 200):

```json
[
  {
    "name": "code-review",
    "description": "Prüft Pull Requests systematisch auf Bugs.",
    "enabled": true
  }
]
```

* `enabled` gibt an, ob der Skill per `jclaw.agent.skills.enabled` aktiviert ist.

### Verfügbare Plugins auflisten

`GET /api/v1/plugins`

Antwort (HTTP 200):

```json
[
  {
    "id": "acme/demo",
    "name": "Demo Plugin",
    "version": "1.2.0",
    "description": "Test-Plugin.",
    "type": "OPENCLAW",
    "valid": true,
    "validationMessage": ""
  }
]
```

* `type` ist eines von `OPENCLAW`, `AGENT_PLUGINS`, `CODEX`, `CLAUDE`, `CURSOR`.
* `valid`/`validationMessage` zeigen das Ergebnis der Control-Plane-Validierung (ungültige Plugins werden nicht verworfen, sondern ausgewiesen).

### Konversationsverlauf einer contextId abrufen

`GET /api/v1/conversations/{contextId}`

Antwort (HTTP 200):

```json
[
  {
    "role": "USER",
    "text": "Berechne 2+2"
  },
  {
    "role": "ASSISTANT",
    "text": "4"
  }
]
```

* `role` ist eine der Rollen `SYSTEM`, `USER`, `ASSISTANT` oder `TOOL`.
* Für unbekannte oder leere `contextId` wird eine leere Liste zurückgegeben.

### Konversation löschen

`DELETE /api/v1/conversations/{contextId}`

Löscht den gesamten gespeicherten Verlauf einer `contextId` (idempotent). Antwort (HTTP 204, ohne Body).

### Alle Sessions auflisten

`GET /api/v1/sessions`

Antwort (HTTP 200):

```json
[
  {
    "sessionId": "s1",
    "displayName": "Hallo Welt",
    "sessionStartedAt": "2026-08-16T10:00:00Z",
    "lastInteractionAt": "2026-08-16T10:30:00Z",
    "updatedAt": "2026-08-16T10:30:00Z"
  }
]
```

### Einzelne Session abrufen

`GET /api/v1/sessions/{sessionId}`

Gibt eine einzelne Session zurück oder 404, wenn sie nicht existiert.

### Session löschen

`DELETE /api/v1/sessions/{sessionId}`

Löscht Session-Metadaten und zugehörige Konversationsnachrichten (idempotent). Antwort (HTTP 204, ohne Body).

## Control-UI

Unter `http://localhost:8080` liefert Spring Boot eine schlanke Web-Oberfläche (`src/main/resources/static/`) zur Steuerung des Agenten aus — bewusst ohne Build-Schritt und ohne externe CDN-Abhängigkeiten (Vanilla-JS + `fetch`):

| Bereich | Funktion |
|---|---|
| **Agent** | Aufgabe + optionale `contextId` eingeben, Ausführung anstoßen; Antwort mit Anzahl der Iterationen und aufklappbaren Tool-Aufrufen |
| **Sessions** | Verfügbare Sessions mit Titel und Zeitstempel auflisten; Klick auf eine Session lädt sie in den Agent; Session löschen; **Session-Gruppen** zuweisen |
| **Konversationen** | Verlauf einer `contextId` laden (als Chat) und löschen |
| **Skills** | Alle geladenen Skills mit Aktivierungsstatus |
| **Plugins** | Erkannte Manifeste mit Typ, Version und Validierungsstatus |

Die UI rendert alle Daten ausschließlich über `textContent` (kein `innerHTML`-Einsatz für API-Daten) und zeigt API-Fehler (`{"error": …}`) direkt an. Auf Mobile (≤ 720 px) wechselt das Layout in eine gestapelte Darstellung. Die statischen Ressourcen werden von `ControlUiResourceTest` geprüft.

**Color-Schemes (P2-09):** Die UI unterstützt Light- und Dark-Theme über CSS-Variablen. Der Theme-Toggle in der Sidebar wechselt zwischen den Themes; die Präferenz wird in `localStorage` persistiert. Das Dark-Theme invertiert Sidebar, Surface und Hintergrund mit angepassten Kontrasten.

### Gateway-Steuerung (P2-04)

Zusätzlich zur Agent-API stehen Gateway-Endpoints zur Verfügung:

| Endpoint | Methode | Beschreibung |
|---|---|---|
| `/api/v1/sessions?group={group}` | GET | Sessions nach Gruppe filtern |
| `/api/v1/sessions/{id}/group` | PUT | Session-Gruppe setzen (`{"group": "work"}`) |
| `/api/v1/sessions/{id}/transcript` | GET | Konversation als JSON-Array exportieren (`role`/`text`) |
| `/api/v1/gateway/status` | GET | Gateway-Status (running, Uptime, Startzeit) |
| `/api/v1/gateway/info` | GET | System-Informationen (Name, Version, Port, Konfiguration) |
| `/api/v1/auth/tokens` | GET | Alle API-Token auflisten (ohne Token-Werte) |
| `/api/v1/auth/tokens` | POST | Neuen API-Token erstellen (`{"name": "..."}`) |
| `/api/v1/auth/tokens/{id}` | DELETE | API-Token löschen |
| `/api/v1/cron-jobs` | GET | Alle Cron-Jobs auflisten |
| `/api/v1/cron-jobs` | POST | Neuen Cron-Job erstellen (`{"name": "...", "cronExpression": "0 */6 * * *", "prompt": "..."}`) |
| `/api/v1/cron-jobs/{id}` | GET | Einzelnen Cron-Job abfragen |
| `/api/v1/cron-jobs/{id}` | PUT | Cron-Job aktualisieren |
| `/api/v1/cron-jobs/{id}` | DELETE | Cron-Job löschen |
| `/api/v1/cron-jobs/{id}/execute` | POST | Cron-Job manuell ausführen |
| `/api/v1/memory/{contextId}/sync` | POST | Konversation als Vault-Dokument materialisieren |
| `/api/v1/memory` | GET | Alle Memory-Vault-Dokumente auflisten |
| `/api/v1/channels` | GET | Alle Channels auflisten |
| `/api/v1/channels` | POST | Neuen Channel erstellen |
| `/api/v1/channels/{id}` | GET | Einzelnen Channel abfragen |
| `/api/v1/channels/{id}` | PUT | Channel aktualisieren |
| `/api/v1/channels/{id}` | DELETE | Channel löschen |
| `/api/v1/channels/{id}/send` | POST | Nachricht über Channel senden |
| `/api/v1/channels/{id}/inbound` | POST | Eingehende Nachricht verarbeiten |
| `/api/v1/channels/{id}/bindings` | GET | Bindungen eines Channels auflisten |
| `/api/v1/channels/{id}/bindings` | POST | Neue Bindung erstellen |
| `/api/v1/channels/{id}/bindings/{bindingId}` | DELETE | Bindung löschen |
| `/api/v1/channels/adapters` | GET | Verfügbare Channel-Adapter auflisten |

### Auth (P2-06)

API-Token-Authentifizierung für die REST-API:

* **Aktivieren:** `jclaw.auth.enabled=true` in `application.properties` oder `openclaw.json`
* **Token erstellen:** `POST /api/v1/auth/tokens` mit `{"name": "mein-token"}` — gibt das rohe Token nur einmalig zurück
* **Token verwenden:** `Authorization: Bearer <token>` in HTTP-Headern
* **Öffentliche Pfade:** `/api/v1/gateway/status`, Static Resources, Auth-Endpunkte sind immer ohne Token erreichbar

### Hooks (P1-11)

Lifecycle-Hooks via `HOOK.md`-Scripts:

* **Aktivieren:** `jclaw.hooks.enabled=true` in `application.properties` oder `openclaw.json`
* **HOOK.md-Format:** YAML-Frontmatter mit `name`, `stage`, `priority`, `script` (optional)
* **Stages:** `gateway_start`, `gateway_stop`, `before_agent_run`, `after_agent_run`, `before_tool_call`, `after_tool_call`
* **Script-Ausführung:** `ProcessBuilder` mit `JCLAW_HOOK_*`-Umgebungsvariablen; Exit-Code 0 = proceed, alles andere = block
* **Priorität:** Hooks werden absteigend nach `priority` ausgeführt (höher = früher)
* **Timeout:** `jclaw.hooks.script-timeout` (Standard: 30s)

### Compaction (P1-10)

LLM-basierte Kontext-Kompression für lange Konversationen:

* **Aktivieren:** `jclaw.compaction.enabled=true` in `application.properties` oder `openclaw.json`
* **Schwellenwert:** `jclaw.compaction.threshold` (Standard: 20 Nachrichten) — Compaction wird ausgelöst, wenn überschritten
* **Retain:** `jclaw.compaction.retain-count` (Standard: 4) — Jüngste Nachrichten werden nie komprimiert
* **Funktionsweise:** Ältere Nachrichten werden durch eine LLM-Zusammenfassung ersetzt, aktuelle Nachrichten bleiben erhalten

### Open Memory Vault (P4-02)

Materialisiert das Konversations-Memory als **menschenlesbare Markdown-Dokumente** (Memory als Asset statt Cache). Anders als die context-abhängige Compaction überlebt der Vault Compaction, Neustarts und Session-Verluste — Nutzer können das Langzeit-Memory direkt durchsuchen und bearbeiten (z. B. mit Tolaria oder Obsidian).

* **Aktivieren:** `jclaw.memory.vault.enabled=true` in `application.properties` oder `openclaw.json`
* **Verzeichnis:** `jclaw.memory.vault.dir` (Standard: `./vault`) — pro Konversation eine `.md`-Datei mit YAML-Frontmatter (`conversationId`, `title`, `createdAt`, `tags`)
* **H2 bleibt Quelle der Wahrheit:** Der Vault ist ein idempotenter, lesbarer Auszug — kein Ersatz und kein Dual-Write-Problem
* **Bidirektionaler Sync (Read-Back):** Ein `MemoryVaultWatcher` überwacht den Vault-Ordner. User-Änderungen an einer `.md`-Datei werden automatisch erkannt und zurück in die gespeicherte Konversation (H2) ingestet — das Markdown-Format (`**ROLLE**\n\ntext`) ist dabei symmetrisch für Schreiben und Lesen
* **REST-API:** `POST /api/v1/memory/{contextId}/sync` (Konversation als Dokument materialisieren), `GET /api/v1/memory` (alle Vault-Dokumente auflisten)

### Cron-Jobs (P1-12)

Wiederkehrende Agent-Jobs mit Cron-Ausdrücken:

* **Aktivieren:** `jclaw.cron.enabled=true` in `application.properties` oder `openclaw.json`
* **Intervall:** `jclaw.cron.interval` (Standard: 60s) — Wie oft auf fällige Jobs geprüft wird
* **Cron-Syntax:** 5 Felder — `Minute Stunde Tag Monat Wochentag` (z.B. `0 */6 * * *` = alle 6 Stunden)
* **REST-API:** `GET|POST|PUT|DELETE /api/v1/cron-jobs`, `POST /api/v1/cron-jobs/{id}/execute`

### Channels (P3-01)

Abstraktionsschicht für externe Nachrichten-Plattformen (Telegram, Slack, Discord, etc.):

* **Aktivieren:** `jclaw.channels.enabled=true` in `application.properties` oder `openclaw.json`
* **Channel-Typen:** TELEGRAM, SLACK, DISCORD, WHATSAPP, SIGNAL, X, EMAIL, IRC, MATTERMOST, FEISHU, GOOGLE_CHAT, SONSTIGE
* **Session-Bindung:** Externe Thread/DM-IDs werden über `ChannelBinding` (DM oder Thread) einer JClaw-Session zugeordnet
* **Adapter-Interface:** `ChannelAdapter`-Port — Channel-Adapter implementieren `send()`, `isAvailable()`, `startReceiving()`/`stopReceiving()`
* **REST-API:** CRUD für Channels, Senden, Inbound-Verarbeitung, Bindungsverwaltung, Adapter-Liste

#### Telegram (P3-02)

Der `TelegramChannelAdapter` verbindet JClaw mit der Telegram Bot API über **Long-Polling**:

* **Aktivieren:** Channel mit `type: TELEGRAM` und folgender Konfiguration erstellen:
  ```json5
  {
    "name": "Mein Telegram Bot",
    "type": "TELEGRAM",
    "config": {
      "token": "<BOT_TOKEN>",          // Pflicht – von @BotFather
      "pollTimeoutSeconds": 30,        // optional, Long-Polling-Timeout
      "baseUrl": "https://api.telegram.org"  // optional, API-Basis-URL
    }
  }
  ```
* **Senden:** `POST /api/v1/channels/{id}/send` — `chatId` wird aus `threadId` bzw. `senderId` aufgelöst
* **Empfangen:** `startReceiving` (Long-Polling `getUpdates` in einem Daemon-Thread) — eingehende Nachrichten werden an den `InboundMessageHandler` delegiert; der Offset wird per `update_id` fortgeschrieben (keine Duplikate)
* **Verfügbarkeit:** `isAvailable()` liefert `true`, wenn der Channel aktiv ist und ein `token` gesetzt ist

#### Slack (P3-03)

Der `SlackChannelAdapter` verbindet JClaw mit Slack über **Socket Mode** (WebSocket) und die REST-API:

* **Aktivieren:** Channel mit `type: SLACK` und folgender Konfiguration erstellen:
  ```json5
  {
    "name": "Mein Slack App",
    "type": "SLACK",
    "config": {
      "token": "<BOT_TOKEN>",          // Pflicht – z. B. xoxb-…
      "baseUrl": "https://slack.com/api"  // optional, Slack-API-Basis-URL
    }
  }
  ```
  Voraussetzung: die Slack-App muss **Socket Mode** aktiviert haben (Events-API mit Socket Mode, keine Request-URL nötig; Bot-Scope für `chat:write` und die gewünschten Events).
* **Senden:** `POST /api/v1/channels/{id}/send` — `chat.postMessage` mit `Authorization: Bearer <token>`; `channel` wird aus `threadId` bzw. `senderId` aufgelöst, die externe `ts` wird erfasst
* **Empfangen:** `startReceiving` öffnet via `apps.connections.open` eine Socket-Mode-WebSocket-URL und verbindet sich mit dem eingebauten Jakarta-WebSocket-Client; eingehende `events_api`-Envelopes werden per `envelope_id` bestätigt (Ack) und `event_callback`-Nachrichten an den `InboundMessageHandler` delegiert
* **Verfügbarkeit:** `isAvailable()` liefert `true`, wenn der Channel aktiv ist und ein `token` gesetzt ist

#### Discord (P3-04)

Der `DiscordChannelAdapter` verbindet JClaw mit Discord über den **Gateway-WebSocket** und die REST-API:

* **Aktivieren:** Channel mit `type: DISCORD` und folgender Konfiguration erstellen:
  ```json5
  {
    "name": "Mein Discord Bot",
    "type": "DISCORD",
    "config": {
      "token": "<BOT_TOKEN>",          // Pflicht – Bot-Token aus dem Developer Portal
      "baseUrl": "https://discord.com/api/v10",  // optional, REST-API-Basis-URL
      "intents": 4609                  // optional, Gateway-Intents (GUILDS | GUILD_MESSAGES | DIRECT_MESSAGES)
    }
  }
  ```
  Voraussetzung: eine Discord-Bot-Anwendung mit Bot-Token und den nötigen Gateway-Intents (für Server-Nachrichten `GUILD_MESSAGES`, für DMs `DIRECT_MESSAGES`).
* **Senden:** `POST /api/v1/channels/{id}/send` — `POST {baseUrl}/channels/{channelId}/messages` mit `Authorization: Bot <token>`; `channelId` wird aus `threadId` bzw. `senderId` aufgelöst, die externe `id` wird erfasst
* **Empfangen:** `startReceiving` ermittelt via `GET {baseUrl}/gateway` die Gateway-URL und verbindet sich mit dem eingebauten Jakarta-WebSocket-Client; beim `Hello`-Frame (op 10) wird ein `Identify`-Frame (op 2) mit Token und Intents gesendet, Heartbeat-Anfragen (op 1) werden beantwortet, und `MESSAGE_CREATE`-Dispatches werden an den `InboundMessageHandler` delegiert
* **Verfügbarkeit:** `isAvailable()` liefert `true`, wenn der Channel aktiv ist und ein `token` gesetzt ist

#### WhatsApp (P3-05)

Der `WhatsAppChannelAdapter` verbindet JClaw mit WhatsApp über die **Meta WhatsApp Cloud API** (Graph API):

* **Aktivieren:** Channel mit `type: WHATSAPP` und folgender Konfiguration erstellen:
  ```json5
  {
    "name": "Mein WhatsApp Bot",
    "type": "WHATSAPP",
    "config": {
      "token": "<META_ACCESS_TOKEN>",   // Pflicht – System-User-Token der Meta-App
      "phoneNumberId": "<PHONE_NUMBER_ID>", // Pflicht – WhatsApp Business Phone Number ID
      "graphUrl": "https://graph.facebook.com/v21.0", // optional, Graph-API-Basis-URL
      "verifyToken": "<WEBHOOK_VERIFY_TOKEN>" // optional, für den Meta-Webhook-Handshake
    }
  }
  ```
  Voraussetzung: eine Meta-App mit **WhatsApp Business Platform-Cloud-API**-Produkt, ein System-User-Token und eine registrierte WhatsApp-Business-Telefonnummer.
* **Senden:** `POST /api/v1/channels/{id}/send` — `POST {graphUrl}/{phoneNumberId}/messages` mit `Authorization: Bearer <token>` und `{"messaging_product":"whatsapp","to":…,"text":{"body":…}}`; `to` wird aus `threadId` bzw. `senderId` aufgelöst, die externe `messages[0].id` wird erfasst
* **Empfangen:** push-basiert über den **Meta-Webhook** — Meta liefert Nachrichten per Webhook an deine URL (z. B. in den vorhandenen `POST /api/v1/channels/{id}/inbound`-Endpoint). Der Adapter liefert `verifyWebhook()` (Hub-Challenge-Handshake für den GET-Gegencheck) und `inboundFromWebhook()` (Parsen des Meta-Payloads in eine eingehende Nachricht)
* **Verfügbarkeit:** `isAvailable()` liefert `true`, wenn der Channel aktiv ist und `token` sowie `phoneNumberId` gesetzt sind

#### IRC (P3-06)

Der `IrcChannelAdapter` verbindet JClaw über eine **direkte TCP-Verbindung** mit einem IRC-Server:

* **Aktivieren:** Channel mit `type: IRC` und folgender Konfiguration erstellen:
  ```json5
  {
    "name": "Mein IRC Bot",
    "type": "IRC",
    "config": {
      "server": "irc.example.org",      // Pflicht – IRC-Server-Hostname
      "port": 6667,                     // optional, Standard 6667
      "nick": "jclaw",                  // optional, Standard "jclaw"
      "channel": "#general",            // optional, Standard "#general"; fehlendes #/&-Präfix wird ergänzt
      "nickservPassword": "<PASSWORD>"  // optional – wird als PRIVMSG NickServ :IDENTIFY gesendet
    }
  }
  ```
* **Senden:** `POST /api/v1/channels/{id}/send` — `PRIVMSG <target> :<text>`; `target` wird aus `threadId` bzw. `senderId` aufgelöst
* **Empfangen:** `startReceiving` verbindet per TCP-Socket, sendet `NICK`/`USER`/`JOIN` und liest in einem Daemon-Thread eingehende `PRIVMSG`-Zeilen (andere Zeilen wie `PING` oder numerische Replies werden ignoriert)
* **Verfügbarkeit:** `isAvailable()` liefert `true`, wenn der Channel aktiv ist und ein `server` gesetzt ist

#### E-Mail (P3-06)

Der `EmailChannelAdapter` verbindet JClaw über **SMTP** (Senden) und **IMAP** (Empfang) mit einem Mail-Server:

* **Aktivieren:** Channel mit `type: EMAIL` und folgender Konfiguration erstellen:
  ```json5
  {
    "name": "Mein Mail Bot",
    "type": "EMAIL",
    "config": {
      "server": "mail.example.org",      // Pflicht – SMTP- und IMAP-Host
      "username": "jclaw@example.org",   // Pflicht – Login
      "password": "<PASSWORD>",          // Pflicht
      "from": "jclaw@example.org",       // optional, Standard: username
      "smtpPort": 587,                   // optional, Standard 25
      "imapPort": 143,                   // optional, Standard 143
      "imapFolder": "INBOX",             // optional, Standard "INBOX"
      "pollIntervalSeconds": 30,         // optional, Standard 30
      "subject": "JClaw",                // optional, Betreff für ausgehende Nachrichten
      "useTls": false                    // optional, Implicit-TLS (SSLSocket)
    }
  }
  ```
* **Senden:** `POST /api/v1/channels/{id}/send` — SMTP-Dialog (`EHLO`/`MAIL FROM`/`RCPT TO`/`DATA` mit From/To/Subject/Date, Dot-Stuffing, `QUIT`); Empfänger wird aus `threadId` bzw. `senderId` aufgelöst
* **Empfangen:** `startReceiving` pollt per IMAP (`LOGIN`/`SELECT`/`UID SEARCH UNSEEN`/`UID FETCH (RFC822)`/`UID STORE +FLAGS (\Seen)`) in einem Daemon-Thread und parsed eingehende Nachrichten mit Jakarta Mail (text/plain bevorzugt, HTML-Fallback)
* **Verfügbarkeit:** `isAvailable()` liefert `true`, wenn der Channel aktiv ist und `server`, `username` sowie `password` gesetzt sind

## OpenClaw-Versionsmonitor

Ein **wöchentlicher GitHub-Workflow** (`.github/workflows/openclaw-monitor.yml`) hält JClaw über neue OpenClaw-Versionen und Community-Feature-Wünsche auf dem Laufenden und prüft sie automatisch gegen die JClaw-Vision (100 % Parität — zuletzt geprüfte Version in `.github/state/openclaw-last-checked.txt`):

* **Release-Scan:** Vergleicht die neueste stabile OpenClaw-Version (`openclaw/openclaw` Releases) mit der zuletzt geprüften Version.
* **Community-Scan:** Durchsucht OpenClaw-Issues/PRs der letzten 7 Tage nach neuen Feature-Requests (Labels `feature`/`enhancement`/`rfc`).
* **Triage-Issue:** Bei Neuigkeiten legt der Workflow automatisch ein GitHub-Issue an — mit Zusammenfassung neuer Features/Wünsche und einer **Checkliste zum Abgleich gegen die JClaw-Vision** (Relevanz → Roadmap-Einordnung → Differenzierung → Priorisierung). Er trifft keine Roadmap-Entscheidung selbst, sondern erzeugt einen Triage-Anreiz.

Ausführung: jeden Montag 08:00 UTC sowie manuell über den Workflow-Tab (parameter `scan_days`). Der Workflow committet die STATE-Datei nach jedem Lauf, sodass er nur bei echten Neuigkeiten feuert.

## Tests

```bash
mvnw test
```

## Lizenz

JClaw ist unter der **Apache License, Version 2.0** lizenziert — siehe [LICENSE](LICENSE). Die Lizenz erlaubt Nutzung, Modifikation und Verteilung (auch kommerziell), inklusive explizitem Patent-Grant. Der Maven-Wrapper (`mvnw`/`mvnw.cmd`) unterliegt der Apache-2.0-Lizenz der ASF.
