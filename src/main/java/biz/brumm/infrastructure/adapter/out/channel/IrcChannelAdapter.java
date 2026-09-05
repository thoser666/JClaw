package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * IRC-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für IRC (RFC 1459/2812) über eine direkte
 * TCP-Verbindung:
 * <ul>
 *   <li><b>Senden:</b> {@code PRIVMSG &lt;channel&gt; :&lt;text&gt;}</li>
 *   <li><b>Empfang:</b> Verbindung mit {@code NICK}/{@code USER}/{@code JOIN} aufbauen
 *       und eingehende {@code PRIVMSG}-Zeilen in {@link ChannelMessage} parsen
 *       (Daemon-Thread, vergleichbar dem Long-Polling bei Telegram).</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code server} – Hostname des IRC-Servers (Pflicht)</li>
 *   <li>{@code port} – Port (optional, Standard 6667)</li>
 *   <li>{@code nick} – Bot-Nickname (optional, Standard {@code jclaw})</li>
 *   <li>{@code channel} – Ziel-Channel (optional, Standard {@code #general}); Präfix {@code #}
 *       wird ergänzt, falls fehlend</li>
 *   <li>{@code nickservPassword} – optional, wird als {@code PRIVMSG NickServ IDENTIFY} gesendet</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class IrcChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(IrcChannelAdapter.class);

    static final String CONFIG_SERVER = "server";
    static final String CONFIG_PORT = "port";
    static final String CONFIG_NICK = "nick";
    static final String CONFIG_CHANNEL = "channel";
    static final String CONFIG_NICKSERV_PASSWORD = "nickservPassword";
    static final String DEFAULT_NICK = "jclaw";
    static final String DEFAULT_CHANNEL = "#general";
    static final int DEFAULT_PORT = 6667;
    static final String IRC_PREFIX = "#";
    static final String IRC_AMP = "&";

    private final IrcConnector connector;
    private volatile IrcSession session;
    private volatile Thread workerThread;

    public IrcChannelAdapter() {
        this(IrcChannelAdapter::connect);
    }

    IrcChannelAdapter(IrcConnector connector) {
        this.connector = connector;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.IRC;
    }

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String target = resolveTarget(message);
        if (target == null || target.isBlank()) {
            throw new ChannelException("Kein IRC-Ziel (channel/threadId/senderId) fuer die Nachricht vorhanden.");
        }
        IrcSession s = session;
        if (s == null || !s.isConnected()) {
            throw new ChannelException("IRC-Verbindung ist nicht aktiv.");
        }
        try {
            s.writeLine("PRIVMSG " + target + " :" + message.content());
        } catch (IOException e) {
            throw new ChannelException("IRC PRIVMSG fehlgeschlagen: " + e.getMessage(), e);
        }
        return ChannelMessage.outbound(channel.id(), message.content(), target, message.sessionId());
    }

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        String server = configString(channel, CONFIG_SERVER);
        return server != null && !server.isBlank();
    }

    @Override
    public synchronized void startReceiving(Channel channel, InboundMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("InboundMessageHandler darf nicht null sein.");
        }
        stopReceiving(channel);
        String server = configString(channel, CONFIG_SERVER);
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("IRC-Server fehlt im Channel-Konfiguration.");
        }
        int port = port(channel);
        try {
            IrcSession s = connector.connect(server, port);
            session = s;
            s.writeLine("NICK " + nick(channel));
            s.writeLine("USER " + nick(channel) + " 0 * :" + nick(channel));
            String password = configString(channel, CONFIG_NICKSERV_PASSWORD);
            if (password != null && !password.isBlank()) {
                s.writeLine("PRIVMSG NickServ :IDENTIFY " + password);
            }
            s.writeLine("JOIN " + channelName(channel));
            log.info("IRC verbunden: server={}, nick={}, channel={}", server, nick(channel), channelName(channel));

            Thread t = new Thread(() -> runReadLoop(channel, handler, s), "irc-read-" + channel.id());
            t.setDaemon(true);
            workerThread = t;
            t.start();
        } catch (IOException e) {
            session = null;
            throw new IllegalArgumentException("IRC-Verbindung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    @Override
    public void stopReceiving(Channel channel) {
        Thread t = workerThread;
        workerThread = null;
        IrcSession s = session;
        session = null;
        if (t != null) {
            t.interrupt();
        }
        if (s != null) {
            try {
                s.writeLine("QUIT :JClaw");
            } catch (IOException ignored) {
                // Socket wird ohnehin geschlossen
            }
            s.close();
        }
        if (t != null || s != null) {
            log.info("IRC fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    // --- Internes Lesen ---

    private void runReadLoop(Channel channel, InboundMessageHandler handler, IrcSession s) {
        try {
            String line;
            while (!Thread.currentThread().isInterrupted() && (line = s.readLine()) != null) {
                ChannelMessage msg = toInboundMessage(channel, line);
                if (msg != null) {
                    handler.onMessage(msg);
                }
            }
        } catch (IOException e) {
            log.warn("IRC-Lesefehler auf '{}': {}", channel.name(), e.getMessage());
        } finally {
            s.close();
        }
        log.info("IRC-Leseschleife fuer Channel '{}' beendet.", channel.name());
    }

    private ChannelMessage toInboundMessage(Channel channel, String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // PRIVMSG-Format:  :sender!user@host PRIVMSG <target> :<text>
        if (!line.startsWith(":")) {
            return null;
        }
        int spaceAfterPrefix = line.indexOf(' ');
        if (spaceAfterPrefix < 0) {
            return null;
        }
        String source = line.substring(1, spaceAfterPrefix);
        String rest = line.substring(spaceAfterPrefix + 1).trim();
        if (!rest.startsWith("PRIVMSG ")) {
            return null;
        }
        String afterCommand = rest.substring("PRIVMSG ".length());
        int targetEnd = afterCommand.indexOf(' ');
        if (targetEnd < 0) {
            return null;
        }
        String target = afterCommand.substring(0, targetEnd);
        String text = afterCommand.substring(targetEnd + 1);
        if (!text.startsWith(":")) {
            return null;
        }
        text = text.substring(1);
        if (text.isBlank()) {
            return null;
        }
        String sender = source;
        String senderName = source;
        int bang = source.indexOf('!');
        if (bang >= 0) {
            sender = source.substring(0, bang);
        }
        return new ChannelMessage(UUID.randomUUID().toString(), channel.id(),
                null, MessageDirection.INBOUND, text, sender, senderName, target, null, Instant.now());
    }

    // --- Helfer ---

    private String resolveTarget(ChannelMessage message) {
        if (message.threadId() != null && !message.threadId().isBlank()) {
            return message.threadId();
        }
        if (message.senderId() != null && !message.senderId().isBlank()) {
            return message.senderId();
        }
        return null;
    }

    private static int port(Channel channel) {
        Object v = channel.config().get(CONFIG_PORT);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        return DEFAULT_PORT;
    }

    private static String nick(Channel channel) {
        String nick = configString(channel, CONFIG_NICK);
        if (nick == null || nick.isBlank()) {
            return DEFAULT_NICK;
        }
        return nick;
    }

    static String channelName(Channel channel) {
        String name = configString(channel, CONFIG_CHANNEL);
        if (name == null || name.isBlank()) {
            return DEFAULT_CHANNEL;
        }
        if (!name.startsWith(IRC_PREFIX) && !name.startsWith(IRC_AMP)) {
            return IRC_PREFIX + name;
        }
        return name;
    }

    private static String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }

    // --- Socket-Abstraktion für Testbarkeit ---

    /**
     * Stellt eine IRC-Verbindung her. Im Produktivfall eine direkte TCP-Socket-Verbindung;
     * in Tests eine Fake-Session, die vorbestellte Zeilen liefert.
     */
    @FunctionalInterface
    interface IrcConnector {
        IrcSession connect(String server, int port) throws IOException;
    }

    /**
     * Abstraktion einer offenen IRC-Session (Lesen/Schreiben/Schließen).
     */
    interface IrcSession {
        String readLine() throws IOException;

        void writeLine(String line) throws IOException;

        void close();

        default boolean isConnected() {
            return true;
        }
    }

    private static IrcSession connect(String server, int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(server, port), 15_000);
        socket.setSoTimeout(0); // unbegrenzt lesen; stopReceiving schließt den Socket
        return new SocketIrcSession(socket);
    }

    static final class SocketIrcSession implements IrcSession {
        private final Socket socket;
        private final BufferedReader reader;
        private final Writer writer;

        SocketIrcSession(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }

        @Override
        public String readLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void writeLine(String line) throws IOException {
            writer.write(line);
            writer.write("\r\n");
            writer.flush();
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}