package biz.brumm.domain.service;

/**
 * Fehler bei einer Skill-Workshop-Operation.
 *
 * @param kind {@link Kind#NOT_FOUND} → HTTP 404, {@link Kind#CONFLICT} → HTTP 409
 *             (No-Clobber, Hash-Stale, nicht-PENDING, maxPending), {@link Kind#APPROVAL_REQUIRED} → HTTP 403.
 */
public class SkillWorkshopException extends RuntimeException {

    public enum Kind {
        NOT_FOUND,
        CONFLICT,
        APPROVAL_REQUIRED
    }

    private final Kind kind;

    public SkillWorkshopException(String message, Kind kind) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}