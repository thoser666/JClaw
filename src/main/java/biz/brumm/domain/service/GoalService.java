package biz.brumm.domain.service;

import biz.brumm.domain.model.GoalStatus;
import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.port.out.GoalStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Orchestriert das dauerhafte Session-Ziel (OpenClaw-Kompatibilität, Punkt P4-07).
 *
 * <p>Spiegel der OpenClaw-Slash-Aktionen {@code /goal start|edit|pause|resume|block|complete|clear}:
 * maximal ein Ziel pro Session, {@code complete} ist terminal, ein abgeschlossenes Ziel
 * wird erst durch {@code clear} ersetzbar.</p>
 */
@Service
public class GoalService {

    private final GoalStore goalStore;

    public GoalService(GoalStore goalStore) {
        this.goalStore = goalStore;
    }

    public Optional<SessionGoal> getGoal(String sessionId) {
        return goalStore.findBySessionId(sessionId);
    }

    public SessionGoal startGoal(String sessionId, String objective, Long tokenBudget) {
        requireNonBlank(sessionId, "Session-ID darf nicht leer sein.");
        requireNonBlank(objective, "Objective darf nicht leer sein.");
        if (tokenBudget != null && tokenBudget <= 0) {
            throw new IllegalArgumentException("Token-Budget muss positiv sein.");
        }
        if (goalStore.findBySessionId(sessionId).isPresent()) {
            throw new GoalOperationException(
                    "Goal error: Es existiert bereits ein Ziel für diese Session. Erst 'clear' verwenden, um ein neues Ziel zu starten.",
                    false);
        }
        Instant now = Instant.now();
        SessionGoal goal = new SessionGoal(sessionId, objective, GoalStatus.ACTIVE, null, tokenBudget, now, null);
        return goalStore.save(goal);
    }

    public SessionGoal rewordGoal(String sessionId, String objective) {
        requireNonBlank(objective, "Objective darf nicht leer sein.");
        SessionGoal goal = requireGoal(sessionId);
        return goalStore.save(goal.withObjective(objective));
    }

    public SessionGoal pauseGoal(String sessionId, String note) {
        SessionGoal goal = requireGoal(sessionId);
        if (goal.status() == GoalStatus.COMPLETE || goal.status() == GoalStatus.BLOCKED
                || goal.status() == GoalStatus.BUDGET_LIMITED || goal.status() == GoalStatus.USAGE_LIMITED) {
            throw new GoalOperationException("Ziel im Status " + goal.status() + " kann nicht pausiert werden.", false);
        }
        return goalStore.save(goal.withStatus(GoalStatus.PAUSED, note, Instant.now()));
    }

    public SessionGoal resumeGoal(String sessionId, String note) {
        SessionGoal goal = requireGoal(sessionId);
        if (goal.status() == GoalStatus.COMPLETE) {
            throw new GoalOperationException(
                    "Abgeschlossenes Ziel kann nicht fortgesetzt werden. Erst 'clear' verwenden.", false);
        }
        if (goal.status() == GoalStatus.ACTIVE) {
            return goalStore.save(goal.withNote(note));
        }
        return goalStore.save(goal.withStatus(GoalStatus.ACTIVE, note, Instant.now()));
    }

    public SessionGoal blockGoal(String sessionId, String note) {
        SessionGoal goal = requireGoal(sessionId);
        if (goal.status() == GoalStatus.COMPLETE || goal.status() == GoalStatus.BUDGET_LIMITED
                || goal.status() == GoalStatus.USAGE_LIMITED) {
            throw new GoalOperationException("Ziel im Status " + goal.status() + " kann nicht geblockt werden.", false);
        }
        return goalStore.save(goal.withStatus(GoalStatus.BLOCKED, note, Instant.now()));
    }

    public SessionGoal completeGoal(String sessionId, String note) {
        SessionGoal goal = requireGoal(sessionId);
        return goalStore.save(goal.withStatus(GoalStatus.COMPLETE, note, Instant.now()));
    }

    public void clearGoal(String sessionId) {
        goalStore.deleteBySessionId(sessionId);
    }

    private SessionGoal requireGoal(String sessionId) {
        return goalStore.findBySessionId(sessionId)
                .orElseThrow(() -> new GoalOperationException("Für diese Session existiert kein Ziel.", true));
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}