package biz.brumm.domain.model;

import java.time.Instant;

/**
 * Ein nachgelagerter Folge-Prompt in der Follow-up-Queue einer Session.
 *
 * <p>Follow-ups werden beim Enqueue nur persistiert und erst durch ein explizites
 * {@code drain} an den Agenten ausgeführt (Deny-by-Default, keine Auto-Ausführung).</p>
 *
 * @param id          Eindeutige ID
 * @param sessionId   Ziel-Session
 * @param prompt      Der Prompt, der beim Drain an den Agenten gesendet wird
 * @param createdAt   Erstellungszeitpunkt
 * @param deliveredAt Zeitpunkt der Ausführung (null = noch offen)
 */
public record FollowUp(String id, String sessionId, String prompt, Instant createdAt, Instant deliveredAt) {

    public FollowUp {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Follow-Up-ID darf nicht leer sein.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session-ID darf nicht leer sein.");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Prompt darf nicht leer sein.");
        }
    }

    public FollowUp withDelivered(Instant delivered) {
        return new FollowUp(id, sessionId, prompt, createdAt, delivered);
    }
}