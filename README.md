# JClaw

JClaw ist ein autonomer, hochgradig strukturierter Software-Agent. Das Projekt ist ein moderner Java- und Spring-basierter Port von OpenClaw, optimiert für lokale LLM-Infrastrukturen via Ollama.

Die Anwendung ist strikt nach den Prinzipien der **Hexagonalen Architektur** (Ports and Adapters) aufgebaut, um die Kern-Domainlogik vollständig von Infrastruktur-Entscheidungen (wie dem spezifischen KI-Provider oder Web-Frameworks) zu entkoppeln.

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
* **Skills (OpenClaw/AgentSkills-Format):** Skills aus dem konfigurierten Verzeichnis (`SKILL.md` mit YAML-Frontmatter) werden in den System-Prompt injiziert, sobald sie per `jclaw.agent.skills.enabled` aktiviert sind.
* **Konversations-Memory:** Über eine optionale `contextId` wird der Gesprächsverlauf (Message-Window mit begrenzter Nachrichtenanzahl) pro Kontext gespeichert und bei Folgeanfragen wieder eingespielt. Die Nachrichten werden persistent in einer H2-Datei-Datenbank abgelegt (`./data/jclaw.mv.db`) und überleben so App-Neustarts.
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
| `jclaw.agent.skills.dir` | `./skills` | Verzeichnis mit Skill-Ordnern (`SKILL.md`) |
| `jclaw.agent.skills.enabled` | `-` (leer) | Namen der zu ladenden Skills (leer = keine Skills aktiv) |

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

## Tests

```bash
mvnw test
```
