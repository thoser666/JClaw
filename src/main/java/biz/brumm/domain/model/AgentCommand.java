package biz.brumm.domain.model;

public record AgentCommand(String prompt, String contextId) {
    public AgentCommand {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt darf nicht leer sein.");
        }
    }
}