package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.infrastructure.adapter.out.channel.IrcChannelAdapter.IrcConnector;
import biz.brumm.infrastructure.adapter.out.channel.IrcChannelAdapter.IrcSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IrcChannelAdapterTest {

    private Channel channel(String server, int port, Map<String, Object> extra) {
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("server", server);
        cfg.put("port", port);
        cfg.putAll(extra);
        return new Channel("irc1", "IRC Test", ChannelType.IRC, true, cfg, Instant.now(), Instant.now());
    }

    // --- Grundlagen ---

    @Test
    void channelTypeIsIrc() {
        assertThat(new IrcChannelAdapter().channelType()).isEqualTo(ChannelType.IRC);
    }

    @Test
    void availabilityRequiresEnabledAndServer() {
        IrcChannelAdapter adapter = new IrcChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.IRC, true, Map.of("server", "irc.example.org"),
                Instant.now(), Instant.now());
        Channel noServer = new Channel("c", "n", ChannelType.IRC, true, Map.of(),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.IRC, false, Map.of("server", "x"),
                Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noServer)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    @Test
    void channelNameAddsHashPrefix() {
        Channel noPrefix = new Channel("c", "n", ChannelType.IRC, true, Map.of("channel", "general"),
                Instant.now(), Instant.now());
        Channel withPrefix = new Channel("c", "n", ChannelType.IRC, true, Map.of("channel", "#tech"),
                Instant.now(), Instant.now());
        Channel ampPrefix = new Channel("c", "n", ChannelType.IRC, true, Map.of("channel", "&local"),
                Instant.now(), Instant.now());

        assertThat(IrcChannelAdapter.channelName(noPrefix)).isEqualTo("#general");
        assertThat(IrcChannelAdapter.channelName(withPrefix)).isEqualTo("#tech");
        assertThat(IrcChannelAdapter.channelName(ampPrefix)).isEqualTo("&local");
    }

    // --- Senden ---

    @Test
    void sendWritesPrivmsgToSession() throws Exception {
        FakeSession session = new FakeSession();
        IrcChannelAdapter adapter = new IrcChannelAdapter((server, port) -> session);
        Channel ch = channel("irc.example.org", 6667, Map.of("channel", "#tech"));

        adapter.startReceiving(ch, m -> { });
        ChannelMessage sent = adapter.send(ch, ChannelMessage.outbound(ch.id(), "Hallo IRC", "#tech", null));
        adapter.stopReceiving(ch);

        assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
        assertThat(sent.threadId()).isEqualTo("#tech");
        assertThat(session.written).contains("PRIVMSG #tech :Hallo IRC");
    }

    @Test
    void sendWithoutTargetThrows() throws Exception {
        FakeSession session = new FakeSession();
        IrcChannelAdapter adapter = new IrcChannelAdapter((server, port) -> session);
        Channel ch = channel("irc.example.org", 6667, Map.of());

        assertThatThrownBy(() -> adapter.send(ch, ChannelMessage.outbound(ch.id(), "text", null, null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Ziel");
    }

    @Test
    void sendWithoutActiveSessionThrows() {
        IrcChannelAdapter adapter = new IrcChannelAdapter((server, port) -> new FakeSession());
        Channel ch = channel("irc.example.org", 6667, Map.of());
        // keine startReceiving → session == null
        assertThatThrownBy(() -> adapter.send(ch, ChannelMessage.outbound(ch.id(), "text", "#x", null)))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("nicht aktiv");
    }

    // --- Empfang ---

    @Test
    void startReceivingJoinsChannelAndParsesPrivmsg() throws Exception {
        List<String> written = new CopyOnWriteArrayList<>();
        IrcConnector connector = (server, port) -> new FakeSession(written);
        IrcChannelAdapter adapter = new IrcChannelAdapter(connector);
        Channel ch = channel("irc.example.org", 6667, Map.of("nick", "jclaw-bot", "channel", "general"));
        AtomicReference<ChannelMessage> received = new AtomicReference<>();

        adapter.startReceiving(ch, received::set);
        FakeSession.getLast().deliver(":max!user@host PRIVMSG #general :Hallo von IRC");
        waitFor(() -> received.get() != null);
        adapter.stopReceiving(ch);

        assertThat(written).contains("NICK jclaw-bot", "USER jclaw-bot 0 * :jclaw-bot", "JOIN #general");
        ChannelMessage msg = received.get();
        assertThat(msg.content()).isEqualTo("Hallo von IRC");
        assertThat(msg.senderId()).isEqualTo("max");
        assertThat(msg.senderName()).isEqualTo("max!user@host");
        assertThat(msg.threadId()).isEqualTo("#general");
        assertThat(msg.direction()).isEqualTo(MessageDirection.INBOUND);
    }

    @Test
    void startReceivingSendsNickservIdentifyWhenConfigured() throws Exception {
        List<String> written = new CopyOnWriteArrayList<>();
        IrcConnector connector = (server, port) -> new FakeSession(written);
        IrcChannelAdapter adapter = new IrcChannelAdapter(connector);
        Channel ch = channel("irc.example.org", 6667,
                Map.of("nick", "jclaw", "channel", "#tech", "nickservPassword", "geheim"));
        AtomicReference<ChannelMessage> received = new AtomicReference<>();

        adapter.startReceiving(ch, received::set);
        FakeSession.getLast().deliver("NOTICE Auth :You are now identified");
        waitFor(() -> written.size() >= 4);
        adapter.stopReceiving(ch);

        assertThat(written).contains("PRIVMSG NickServ :IDENTIFY geheim");
    }

    @Test
    void stopsReceivingQuitsAndClosesSession() throws Exception {
        FakeSession session = new FakeSession();
        IrcChannelAdapter adapter = new IrcChannelAdapter((server, port) -> session);
        Channel ch = channel("irc.example.org", 6667, Map.of());

        adapter.startReceiving(ch, m -> { });
        adapter.stopReceiving(ch);

        assertThat(session.closed).isTrue();
        assertThat(session.written).contains("QUIT :JClaw");
    }

    // --- Parser ---

    @Test
    void ignoresNonPrivmsgLines() throws Exception {
        FakeSession session = new FakeSession();
        IrcChannelAdapter adapter = new IrcChannelAdapter((server, port) -> session);
        Channel ch = channel("irc.example.org", 6667, Map.of("channel", "#tech"));
        AtomicReference<ChannelMessage> received = new AtomicReference<>();

        adapter.startReceiving(ch, received::set);
        FakeSession.getLast().deliver("PING :12345");
        FakeSession.getLast().deliver(":server 353 jclaw = #tech :@max +bob");
        Thread.sleep(300);
        adapter.stopReceiving(ch);

        assertThat(received.get()).isNull();
    }

    // --- Helfer ---

    private static void waitFor(CheckedSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.get()).isTrue();
    }

    @FunctionalInterface
    interface CheckedSupplier {
        boolean get() throws Exception;
    }

    /** Fake-Session, die geschriebene Zeilen sammelt und per {@link #deliver} externe Zeilen liefert. */
    private static final class FakeSession implements IrcSession {
        private static final AtomicReference<FakeSession> LAST = new AtomicReference<>();
        private final List<String> written;
        private final BlockingLineQueue queue = new BlockingLineQueue();
        private volatile boolean closed;

        FakeSession() {
            this(new CopyOnWriteArrayList<>());
        }

        FakeSession(List<String> target) {
            this.written = target;
            LAST.set(this);
        }

        static FakeSession getLast() {
            return LAST.get();
        }

        void deliver(String line) {
            queue.offer(line);
        }

        @Override
        public String readLine() {
            try {
                return queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        @Override
        public void writeLine(String line) {
            written.add(line);
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }
    }

    /** Minimal-buffered Queue für Zeilen zwischen Test und Read-Loop. */
    private static final class BlockingLineQueue {
        private final java.util.concurrent.BlockingQueue<String> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();

        void offer(String line) {
            queue.offer(line);
        }

        String take() throws InterruptedException {
            return queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}