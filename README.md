# JClaw

JClaw ist ein autonomer, hochgradig strukturierter Software-Agent. Das Projekt ist ein moderner Java- und Spring-basiertes Port von OpenClaw, optimiert für lokale LLM-Infrastrukturen via Ollama.

Die Anwendung ist strikt nach den Prinzipien der **Hexagonalen Architektur** (Ports and Adapters) aufgebaut, um die Kern-Domainlogik vollständig von Infrastruktur-Entscheidungen (wie dem spezifischen KI-Provider oder Web-Frameworks) zu entkoppeln.

## Tech Stack

* **Java 25** (GraalVM Community)
* **Spring Boot 4.0.6**
* **Spring AI 2.0.0-M7**
* **Ollama** (Default Model: `qwen3:8b`)
* **Maven**

## Architektur-Überblick

Das Projekt folgt der hexagonalen Struktur unter dem Package-Stamm `biz.brumm`:

* `domain.model`: Reine Fachobjekte (`AgentCommand`, `AgentResponse`), frei von Framework-Abhängigkeiten.
* `domain.port.in` / `out`: Schnittstellen für eingehende Befehle (Use Cases) und ausgehende Infrastruktur (KI-Provider).
* `domain.service`: Die Kern-Logik des Agenten, die die Ports orchestriert.
* `infrastructure.adapter`: Die technische Implementierung der Ports (REST-Controller für den Inbound-Verkehr, Ollama-Client für den Outbound-Verkehr).

## Voraussetzungen

1. **Ollama** muss lokal laufen.
2. Das gewünschte Modell muss heruntergeladen sein:
   ```bash
   ollama pull qwen3:8b
