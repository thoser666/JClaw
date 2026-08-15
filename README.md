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
* **Skills (OpenClaw/AgentSkills-Format):** Skills aus dem konfigurierten Verzeichnis (`SKILL.md` mit YAML-Frontmatter) werden in den System-Prompt injiziert, sobald sie per `jclaw.agent.skills.enabled` aktiviert sind.
* **Konversations-Memory:** Über eine optionale `contextId` wird der Gesprächsverlauf (Message-Window mit begrenzter Nachrichtenanzahl) pro Kontext gespeichert und bei Folgeanfragen wieder eingespielt. Die Nachrichten werden persistent in einer H2-Datei-Datenbank abgelegt (`./data/jclaw.mv.db`) und überleben so App-Neustarts.
* **Plugins (Control-Plane):** Plugin-Manifeste im OpenClaw-Format (`openclaw.plugin.json`) sowie kompatible fremde Bundles (Agent Plugins, Codex, Claude, Cursor) werden gelesen und ohne Codeausführung validiert (Pflichtfelder, Schema-Struktur).
* **Node-Sidecar-Bridge (P1-03):** Verwaltete JSON-RPC-Bridge zu einem Node.js-Sidecar-Prozess (Handshake, Call-/Ready-Timeout, strukturierte Fehler, Restart) — Grundlage für die Plugin-Laufzeit in P4-01. Spezifikation: [docs/bridge-protocol.md](docs/bridge-protocol.md).
* **Fehlerbehandlung:** Ein globaler `@RestControllerAdvice` liefert bei ungültigen Anfragen (z. B. leerem Prompt) eine 400-Antwort mit Fehlermeldung.

## Voraussetzungen

1. **Ollama** muss lokal laufen.
2. Das gewünschte Modell muss heruntergeladen sein:
   ```bash
   ollama pull qwen3:8b
   ```

## Konfiguration

Einstellungen in `src/main/resources/application.properties`:

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

* Das Tabellenschema ist in `src/main/resources/schema.sql` definiert (Tabelle `chat_message` mit Primärschlüssel `(conversation_id, message_order)`).
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
  "iterations": 2
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

## Tests

```bash
mvnw test
```
