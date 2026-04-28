# JClaw 🐾

**JClaw** is an autonomous AI agent and a high-performance Java port of the well-known **OpenClaw** project. Built on **Spring Boot 3**, it is optimized for execution as a **GraalVM Native Image**.

JClaw is not just a chatbot; it is a proactive agent that plans tasks, maintains long-term memory, and operates either locally via **Ollama** or through various cloud providers.

## 🚀 Key Features

- **Java-Native Performance:** Powered by GraalVM, JClaw starts in milliseconds and maintains a minimal memory footprint.
- **Provider-Agnostic:** Utilizes **Spring AI** for seamless integration with **Ollama** (local), Anthropic (Claude), or OpenAI.
- **Autonomous Loop:** Implements the ReAct pattern for independent problem-solving.
- **Persistent Memory:** Saves information across sessions (Markdown-based), staying true to the original concept.
- **Gitflow & CI/CD:** Fully integrated GitHub Actions for automated native builds.

## 🛠 Tech Stack

- **Framework:** Spring Boot 3.2+
- **AI Orchestration:** Spring AI
- **Runtime:** GraalVM (JDK 21)
- **Model Integration:** Ollama (Default)

## 📋 Prerequisites

- **Ollama** (installed and running locally)
- **GraalVM JDK 21** (required for native builds)
- **Maven** (or the included wrapper `./mvnw`)

## 🚦 Quick Start

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com
    cd jclaw
    ```

2.  **Prepare Ollama Model:**
    Ensure Ollama is running and pull the default model:
    ```bash
    ollama pull llama3
    ```

3.  **Run the Application:**
    ```bash
    ./mvnw spring-boot:run
    ```

4.  **Compile as Native Image (Optional):**
    ```bash
    ./mvnw -Pnative native:compile
    ./target/jclaw
    ```

## 🏗 Project Structure & Gitflow

This project follows the **Gitflow pattern**:
- `master`: Contains stable production releases.
- `develop`: The primary branch for ongoing development.
- Feature branches are branched off from `develop`.

## 📄 License

This project is licensed under the **MIT License** – see the [LICENSE](LICENSE) file for details.

## 🤝 Credits

Inspired by the original **OpenClaw** project (Clawdbot). JClaw brings the vision of autonomous agents to the Java ecosystem.
