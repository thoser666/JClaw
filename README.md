# JClaw

JClaw ist ein autonomer, hochgradig strukturierter Software-Agent. Das Projekt ist ein moderner Java- und Spring-basierter Port von OpenClaw, optimiert für lokale LLM-Infrastrukturen via Ollama.

Die Anwendung ist strikt nach den Prinzipien der **Hexagonalen Architektur** (Ports and Adapters) aufgebaut, um die Kern-Domainlogik vollständig von Infrastruktur-Entscheidungen (wie dem spezifischen KI-Provider oder Web-Frameworks) zu entkoppeln.

## Tech Stack

* **Java 25** (GraalVM Community)
* **Spring Boot 4.1.0**
* **Spring AI 2.0.0**
* **Ollama** (Default Model: `qwen3:8b`)
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
* **Konversations-Memory:** Über eine optionale `contextId` wird der Gesprächsverlauf (Message-Window mit begrenzter Nachrichtenanzahl) pro Kontext gespeichert und bei Folgeanfragen wieder eingespielt.
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
| `jclaw.agent.max-iterations` | `8` | Maximale Agent-Iterationen (Tool-Runden) |
| `jclaw.agent.max-history-messages` | `10` | Nachrichten pro Kontext im Memory-Fenster |

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
