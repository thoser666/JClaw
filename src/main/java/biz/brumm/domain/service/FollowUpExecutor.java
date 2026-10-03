package biz.brumm.domain.service;

/**
 * Führt einen aus der Follow-up-Queue gehobenen Prompt an der Ziel-Session aus.
 */
@FunctionalInterface
public interface FollowUpExecutor {

    void execute(String sessionId, String prompt);
}