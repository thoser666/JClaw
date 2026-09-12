package biz.brumm.domain.service;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.IngressCursor;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.IngressCursorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Ingress-Monitor-Abstraktion (P3-08).
 * <p>
 * Gemeinsamer, dauerhafter Empfangs-Lifecycle für poll-basierte Channel-Adapter
 * (Vorbild: OpenClaw-Plugin-SDK). Der Monitor orchestriert statt eigener Adapter-Logik:
 * <ul>
 *   <li><b>Polling</b> – periodischer Aufruf des vom Adapter bereitgestellten {@link IngressPoller};
 *       der Poller erhält seine letzte Position ({@code sincePosition}) als Startpunkt.</li>
 *   <li><b>Claim-Identity-Validation</b> – optionale Vorprüfung vor der Übernahme
 *       (z. B. Channel-Typ-/Absender-Check); abgelehnte Claims werden gezählt und übersprungen.</li>
 *   <li><b>Durable admission</b> – übernommene Items werden über den {@link IngressCursorStore}
 *       dauerhaft bestätigt (At-most-once über Neustarts hinweg) und über den Cursor fortgeschrieben.</li>
 *   <li><b>Adoption handoff</b> – übernommene Nachrichten werden an den
 *       {@link ChannelAdapter.InboundMessageHandler} übergeben.</li>
 *   <li><b>Pruning</b> – ältere bestätigte Items können über {@code pruneOlderThan} entfernt werden
 *       (Aufruf liegt beim Adapter/Monitor-Betreiber).</li>
 *   <li><b>Shutdown</b> – {@link IngressSession#stop()} beendet den Daemon-Loop kooperativ.</li>
 * </ul>
 * <p>
 * Konvention: Der {@code IngressPoller} liefert Items in chronologischer Reihenfolge
 * (älteste zuerst); der Cursor zeigt auf das letzte übernommene Item.
 */
@Service
public class ChannelIngressMonitor {

    private static final Logger log = LoggerFactory.getLogger(ChannelIngressMonitor.class);
    private static final String INITIAL_POSITION = "";

    private final IngressCursorStore cursorStore;

    public ChannelIngressMonitor(IngressCursorStore cursorStore) {
        this.cursorStore = cursorStore;
    }

    /**
     * Pollt genau einmal: pollt den Adapter, validiert Claims, nimmt neue Items dauerhaft
     * an (durable admission) und übergibt sie an den Handler (adoption handoff).
     */
    public PollResult pollOnce(Channel channel, IngressPoller poller,
                               ChannelAdapter.InboundMessageHandler handler,
                               Predicate<ChannelMessage> claimValidator, Instant now) throws Exception {
        IngressCursor cursor = cursorStore.loadCursor(channel.id()).orElse(null);
        String since = cursor == null || cursor.lastItemId() == null ? INITIAL_POSITION : cursor.lastItemId();
        List<ChannelMessage> items = poller.poll(since);
        if (items == null) {
            items = List.of();
        }
        int newItems = 0;
        int skipped = 0;
        String lastAdmitted = null;
        for (ChannelMessage item : items) {
            if (claimValidator != null && !claimValidator.test(item)) {
                skipped++;
                continue;
            }
            String id = item.externalId();
            if (id == null || id.isBlank()) {
                skipped++;
                continue;
            }
            if (!cursorStore.admitItem(channel.id(), id, now)) {
                skipped++;
                continue;
            }
            handler.onMessage(item);
            newItems++;
            lastAdmitted = id;
        }
        if (lastAdmitted != null) {
            cursorStore.saveCursor(new IngressCursor(channel.id(), lastAdmitted, now));
        }
        return new PollResult(newItems, skipped, items.size());
    }

    /**
     * Startet den Daemon-Loop (Polling im Intervall) und liefert eine {@link IngressSession}
     * zum kooperativen Stopp.
     */
    public IngressSession start(Channel channel, long pollIntervalSeconds,
                                IngressPoller poller, ChannelAdapter.InboundMessageHandler handler) {
        IngressSession session = new IngressSession();
        Thread t = new Thread(() -> runLoop(channel, pollIntervalSeconds, poller, handler, session),
                "ingress-monitor-" + channel.id());
        t.setDaemon(true);
        session.thread = t;
        t.start();
        log.info("Ingress-Monitor fuer Channel '{}' gestartet (Intervall {}s).", channel.name(), pollIntervalSeconds);
        return session;
    }

    /**
     * Löscht angenommene Items eines Channels, die älter als {@code olderThan} sind (pruning).
     */
    public void pruneOlderThan(Channel channel, Instant olderThan) {
        cursorStore.pruneItemsOlderThan(channel.id(), olderThan);
        log.info("Ingress-Pruning fuer Channel '{}' vor {}: abgeschlossen.", channel.name(), olderThan);
    }

    private void runLoop(Channel channel, long pollIntervalSeconds, IngressPoller poller,
                         ChannelAdapter.InboundMessageHandler handler, IngressSession session) {
        while (session.isRunning() && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce(channel, poller, handler, null, Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Ingress-Poll-Fehler auf '{}': {}", channel.name(), e.getMessage());
                sleepQuietly(Duration.ofSeconds(5));
            }
            sleepQuietly(Duration.ofSeconds(Math.max(1, pollIntervalSeconds)));
        }
        log.info("Ingress-Monitor fuer Channel '{}' beendet.", channel.name());
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Vom Adapter bereitgestellter Poller. Liefert die sichtbaren Nachrichten (älteste zuerst);
     * {@code sincePosition} ist die zuletzt gespeicherte Cursor-Position des Channels.
     */
    @FunctionalInterface
    public interface IngressPoller {
        List<ChannelMessage> poll(String sincePosition) throws Exception;
    }

    /**
     * Ergebnis eines einzelnen Poll-Zyklus.
     */
    public record PollResult(int newItems, int skipped, int total) {
    }

    /**
     * Laufende Monitor-Session; {@link #stop()} beendet den Daemon-Loop kooperativ.
     */
    public static class IngressSession implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile Thread thread;

        public boolean isRunning() {
            return running.get();
        }

        public void stop() {
            running.set(false);
            Thread t = thread;
            thread = null;
            if (t != null && t.isAlive()) {
                t.interrupt();
                log.info("Ingress-Monitor wird gestoppt …");
            }
        }

        @Override
        public void close() {
            stop();
        }
    }
}