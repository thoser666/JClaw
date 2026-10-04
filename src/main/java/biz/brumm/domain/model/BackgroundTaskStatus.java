package biz.brumm.domain.model;

/**
 * Status eines Background-Tasks (Hintergrund-Ausführung einer Session, Punkt P4-08).
 */
public enum BackgroundTaskStatus {

    PENDING,

    RUNNING,

    COMPLETED,

    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}