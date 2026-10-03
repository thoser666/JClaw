package biz.brumm.domain.service;

/**
 * Fehler bei einer Goal-Operation (existierendes Ziel, fehlendes Ziel, ungültiger Statusübergang).
 *
 * @param notFound true = Ziel nicht vorhanden (HTTP 404), false = Konflikt/ungültiger Übergang (HTTP 409)
 */
public class GoalOperationException extends RuntimeException {

    private final boolean notFound;

    public GoalOperationException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public boolean isNotFound() {
        return notFound;
    }
}