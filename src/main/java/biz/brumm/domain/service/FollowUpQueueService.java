package biz.brumm.domain.service;

import biz.brumm.domain.model.FollowUp;
import biz.brumm.domain.port.out.FollowUpQueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Verwaltet die persistente Follow-up-Queue einer Session (OpenClaw-Kompatibilität, Punkt P4-07).
 *
 * <p>Follow-ups werden nur gespeichert und über ein explizites {@code drain} ausgeführt
 * (Deny-by-Default: nach einem Gateway-Neustart wartet die Queue, bis ein Drain angefordert wird).</p>
 */
@Service
public class FollowUpQueueService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpQueueService.class);

    private final FollowUpQueueStore store;
    private final FollowUpExecutor executor;

    public FollowUpQueueService(FollowUpQueueStore store, FollowUpExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    public FollowUp enqueue(String sessionId, String prompt) {
        requireNonBlank(sessionId, "Session-ID darf nicht leer sein.");
        requireNonBlank(prompt, "Prompt darf nicht leer sein.");
        return store.enqueue(new FollowUp(UUID.randomUUID().toString(), sessionId, prompt, Instant.now(), null));
    }

    public List<FollowUp> pending(String sessionId) {
        return store.findPendingBySessionId(sessionId);
    }

    public Optional<FollowUp> findById(String id) {
        return store.findById(id);
    }

    /**
     * Führt alle offenen Follow-ups der Session aus und markiert sie als zugestellt.
     * Ein fehlschlagender Follow-up bleibt in der Queue und blockiert die übrigen nicht.
     *
     * @return die erfolgreich zugestellten Follow-ups
     */
    public List<FollowUp> drain(String sessionId) {
        List<FollowUp> pending = store.findPendingBySessionId(sessionId);
        List<FollowUp> delivered = new ArrayList<>();
        Instant now = Instant.now();
        for (FollowUp followUp : pending) {
            try {
                executor.execute(sessionId, followUp.prompt());
                delivered.add(store.markDelivered(followUp.id(), now));
            } catch (RuntimeException ex) {
                log.warn("Follow-Up '{}' konnte nicht ausgeführt werden und bleibt in der Queue.", followUp.id(), ex);
            }
        }
        return delivered;
    }

    public void cancel(String id) {
        store.deleteById(id);
    }

    private static void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}