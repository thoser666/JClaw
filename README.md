# JClaw 🐾

**JClaw** ist ein autonomer KI-Agent und ein leistungsstarker Java-Port des bekannten **OpenClaw**-Projekts. Er basiert auf **Spring Boot 3** und ist für die Ausführung als **GraalVM Native Image** optimiert.

JClaw ist kein einfacher Chatbot, sondern ein proaktiver Agent, der Aufgaben plant, ein permanentes Gedächtnis besitzt und lokal via **Ollama** oder über Cloud-Provider operieren kann.

## 🚀 Key Features

- **Java-Native Performance:** Dank GraalVM startet JClaw in Millisekunden und verbraucht minimalen Arbeitsspeicher.
- **Provider-Agnostisch:** Nutzt **Spring AI** zur nahtlosen Integration von **Ollama** (lokal), Anthropic (Claude) oder OpenAI.
- **Autonomer Loop:** Implementiert das ReAct-Muster für selbstständiges Problemlösen.
- **Permanentes Gedächtnis:** Speichert Informationen sitzungsübergreifend (Markdown-basiert), genau wie das Original.
- **Gitflow & CI/CD:** Vollständig integrierte GitHub Actions für automatische Native-Builds.

## 🛠 Tech Stack

- **Framework:** Spring Boot 3.2+
- **AI-Orchestration:** Spring AI
- **Runtime:** GraalVM (JDK 21)
- **Modell-Integration:** Ollama (Default)

## 📋 Voraussetzungen

- **Ollama** (lokal installiert und laufend)
- **GraalVM JDK 21** (für Native-Builds)
- **Maven** (oder der beiliegende Wrapper `./mvnw`)

## 🚦 Schnellstart

1.  **Repository klonen:**
    ```bash
    git clone https://github.com
    cd jclaw
    ```

2.  **Ollama Modell vorbereiten:**
    Stelle sicher, dass Ollama läuft und lade das Standard-Modell:
    ```bash
    ollama pull llama3
    ```

3.  **Anwendung starten:**
    ```bash
    ./mvnw spring-boot:run
    ```

4.  **Als Native Image kompilieren (Optional):**
    ```bash
    ./mvnw -Pnative native:compile
    ./target/jclaw
    ```

## 🏗 Projekt-Struktur & Gitflow

Dieses Projekt folgt dem **Gitflow-Muster**:
- `master`: Enthält die stabilen Releases.
- `develop`: Hier findet die tägliche Entwicklung statt.
- Feature-Branches werden von `develop` abgezweigt.

## 📄 Lizenz

Dieses Projekt ist unter der **MIT-Lizenz** lizenziert – siehe die [LICENSE](LICENSE) Datei für Details.

## 🤝 Credits

Inspiriert durch das ursprüngliche **OpenClaw**-Projekt (Clawdbot). JClaw bringt die Vision autonomer Agenten in das Java-Ökosystem.
