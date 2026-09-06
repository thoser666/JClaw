package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E-Mail-Channel-Adapter (P3-06).
 * <p>
 * Implementiert {@link ChannelAdapter} für **E-Mail** über die Standard-Protokolle
 * RFC 5321 (SMTP, Senden) und RFC 3501 (IMAP, Empfang):
 * <ul>
 *   <li><b>Senden:</b> SMTP-Dialog mit {@code EHLO}, {@code MAIL FROM}, {@code RCPT TO},
 *       {@code DATA} (From/To/Subject/Date + Nachrichtentext) und {@code QUIT}.</li>
 *   <li><b>Empfang:</b> IMAP-Polling im Daemon-Thread ({@code LOGIN}, {@code SELECT},
 *       {@code UID SEARCH UNSEEN}, {@code UID FETCH … (RFC822)}, {@code UID STORE … +FLAGS (\Seen)}).
 *       Eingehende Nachrichten werden mit Jakarta Mail (MIME) geparst und als
 *       {@link ChannelMessage} an den {@link InboundMessageHandler} delegiert.</li>
 * </ul>
 * <p>
 * Erwartete Konfiguration im {@code Channel.config}:
 * <ul>
 *   <li>{@code server} – Mail-Server-Host (SMTP + IMAP, Pflicht)</li>
 *   <li>{@code username}/{@code password} – Login (Pflicht)</li>
 *   <li>{@code from} – Absender-Adresse (optional, Standard: {@code username})</li>
 *   <li>{@code smtpPort} – SMTP-Port (optional, Standard 25)</li>
 *   <li>{@code imapPort} – IMAP-Port (optional, Standard 143)</li>
 *   <li>{@code imapFolder} – Polling-Ordner (optional, Standard {@code INBOX})</li>
 *   <li>{@code pollIntervalSeconds} – Polling-Intervall (optional, Standard 30)</li>
 *   <li>{@code subject} – Betreff für ausgehende Nachrichten (optional, Standard {@code JClaw})</li>
 *   <li>{@code useTls} – Implicit-TLS (optional, Standard {@code false})</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.channels", name = "enabled", havingValue = "true")
public class EmailChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelAdapter.class);

    static final String CONFIG_SERVER = "server";
    static final String CONFIG_USERNAME = "username";
    static final String CONFIG_PASSWORD = "password";
    static final String CONFIG_FROM = "from";
    static final String CONFIG_SMTP_PORT = "smtpPort";
    static final String CONFIG_IMAP_PORT = "imapPort";
    static final String CONFIG_IMAP_FOLDER = "imapFolder";
    static final String CONFIG_POLL_INTERVAL = "pollIntervalSeconds";
    static final String CONFIG_SUBJECT = "subject";
    static final String CONFIG_USE_TLS = "useTls";
    static final String DEFAULT_SUBJECT = "JClaw";
    static final String DEFAULT_FOLDER = "INBOX";
    static final int DEFAULT_SMTP_PORT = 25;
    static final int DEFAULT_IMAP_PORT = 143;
    static final int DEFAULT_POLL_INTERVAL = 30;

    private static final Pattern LITERAL = Pattern.compile("\\{(\\d+)\\}$");

    private final SmtpConnector smtpConnector;
    private final ImapConnector imapConnector;
    private final MessageParser parser;
    private volatile Thread workerThread;
    private volatile ImapSession currentSession;
    private volatile boolean running;

    public EmailChannelAdapter() {
        this(EmailChannelAdapter::connectSmtp, EmailChannelAdapter::connectImap, EmailChannelAdapter::parseRaw);
    }

    EmailChannelAdapter(SmtpConnector smtpConnector, ImapConnector imapConnector, MessageParser parser) {
        this.smtpConnector = smtpConnector;
        this.imapConnector = imapConnector;
        this.parser = parser;
    }

    @Override
    public ChannelType channelType() {
        return ChannelType.EMAIL;
    }

    // --- Senden (SMTP) ---

    @Override
    public ChannelMessage send(Channel channel, ChannelMessage message) throws ChannelException {
        if (message.content() == null || message.content().isBlank()) {
            throw new ChannelException("Nachrichteninhalt darf nicht leer sein.");
        }
        String to = resolveRecipient(message);
        if (to == null || to.isBlank() || to.indexOf('@') < 0) {
            throw new ChannelException("Kein gueltiger E-Mail-Empfaenger (threadId/senderId) vorhanden.");
        }
        String server = require(channel, CONFIG_SERVER);
        String username = require(channel, CONFIG_USERNAME);
        String password = require(channel, CONFIG_PASSWORD);
        String from = configString(channel, CONFIG_FROM);
        if (from == null || from.isBlank()) {
            from = username;
        }
        int port = intConfig(channel, CONFIG_SMTP_PORT, DEFAULT_SMTP_PORT);
        boolean tls = boolConfig(channel, CONFIG_USE_TLS);
        String subject = configString(channel, CONFIG_SUBJECT);
        if (subject == null || subject.isBlank()) {
            subject = DEFAULT_SUBJECT;
        }

        SmtpSession session = null;
        try {
            session = smtpConnector.connect(server, port, tls);
            expect(session, 220, null);
            command(session, "EHLO jclaw", 250);
            command(session, "MAIL FROM:<" + from + ">", 250);
            command(session, "RCPT TO:<" + to + ">", 250);
            command(session, "DATA", 354);
            sendMailData(session, from, to, subject, message.content());
            command(session, ".", 250);
            command(session, "QUIT", 221);
        } catch (IOException e) {
            throw new ChannelException("SMTP-Fehler: " + e.getMessage(), e);
        } finally {
            if (session != null) {
                session.close();
            }
        }
        log.info("E-Mail an '{}' gesendet: {}", to,
                message.content().length() > 50
                        ? message.content().substring(0, 50) + "..." : message.content());
        return ChannelMessage.outbound(channel.id(), message.content(), to, message.sessionId());
    }

    private static void sendMailData(SmtpSession session, String from, String to,
                                     String subject, String body) throws IOException {
        MailDateFormat fmt = new MailDateFormat();
        session.writeLine("From: <" + from + ">");
        session.writeLine("To: <" + to + ">");
        session.writeLine("Subject: " + subject);
        session.writeLine("Date: " + fmt.format(Date.from(Instant.now())));
        session.writeLine("");
        for (String line : body.split("\r?\n", -1)) {
            session.writeLine(line.startsWith(".") ? "." + line : line);
        }
    }

    private static void command(SmtpSession session, String cmd, int expected) throws IOException {
        session.writeLine(cmd);
        expect(session, expected, cmd);
    }

    private static void expect(SmtpSession session, int expected, String cmd) throws IOException {
        String reply = session.readLine();
        if (reply == null) {
            throw new IOException("SMTP-Server hat die Verbindung beendet (" + cmd + ").");
        }
        if (!reply.startsWith(String.valueOf(expected))) {
            throw new IOException("SMTP-Fehler (erwartet " + expected + "): " + reply);
        }
    }

    // --- Empfang (IMAP-Polling) ---

    @Override
    public boolean isAvailable(Channel channel) {
        if (channel == null || !channel.enabled()) {
            return false;
        }
        return has(channel, CONFIG_SERVER)
                && has(channel, CONFIG_USERNAME)
                && has(channel, CONFIG_PASSWORD);
    }

    @Override
    public synchronized void startReceiving(Channel channel, InboundMessageHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("InboundMessageHandler darf nicht null sein.");
        }
        stopReceiving(channel);
        require(channel, CONFIG_SERVER);
        require(channel, CONFIG_USERNAME);
        require(channel, CONFIG_PASSWORD);
        int pollInterval = Math.max(1, intConfig(channel, CONFIG_POLL_INTERVAL, DEFAULT_POLL_INTERVAL));

        running = true;
        Thread t = new Thread(() -> runPollLoop(channel, handler, pollInterval), "email-poll-" + channel.id());
        t.setDaemon(true);
        workerThread = t;
        t.start();
        log.info("E-Mail-Polling gestartet: server={}, folder={}", configString(channel, CONFIG_SERVER),
                configString(channel, CONFIG_IMAP_FOLDER) == null
                        ? DEFAULT_FOLDER : configString(channel, CONFIG_IMAP_FOLDER));
    }

    @Override
    public void stopReceiving(Channel channel) {
        running = false;
        Thread t = workerThread;
        workerThread = null;
        ImapSession s = currentSession;
        currentSession = null;
        if (s != null) {
            s.close();
        }
        if (t != null) {
            t.interrupt();
        }
        if (t != null || s != null) {
            log.info("E-Mail-Polling fuer Channel '{}' gestoppt.", channel.name());
        }
    }

    private void runPollLoop(Channel channel, InboundMessageHandler handler, int pollInterval) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce(channel, handler);
            } catch (Exception e) {
                log.warn("E-Mail-Polling auf '{}' fehlgeschlagen: {}", channel.name(), e.getMessage());
            }
            if (!running || Thread.currentThread().isInterrupted()) {
                break;
            }
            sleepQuietly(pollInterval);
        }
        log.info("E-Mail-Leseschleife fuer Channel '{}' beendet.", channel.name());
    }

    private void pollOnce(Channel channel, InboundMessageHandler handler) throws IOException {
        String server = configString(channel, CONFIG_SERVER);
        String username = configString(channel, CONFIG_USERNAME);
        String password = configString(channel, CONFIG_PASSWORD);
        String folder = configString(channel, CONFIG_IMAP_FOLDER);
        if (folder == null || folder.isBlank()) {
            folder = DEFAULT_FOLDER;
        }
        int port = intConfig(channel, CONFIG_IMAP_PORT, DEFAULT_IMAP_PORT);
        boolean tls = boolConfig(channel, CONFIG_USE_TLS);

        ImapSession session = imapConnector.connect(server, port, tls);
        currentSession = session;
        try {
            imapCommand(session, 1, "LOGIN " + username + " " + password);
            imapCommand(session, 2, "SELECT " + folder);
            List<Long> uids = searchUnseen(session, 3);
            int tag = 4;
            for (long uid : uids) {
                if (!running) {
                    break;
                }
                byte[] raw = fetchRaw(session, tag++, uid);
                if (raw == null || raw.length == 0) {
                    continue;
                }
                ParsedEmail email;
                try {
                    email = parser.parse(raw);
                } catch (ChannelException e) {
                    log.warn("E-Mail {} nicht parsbar auf '{}': {} – als gesehen markiert.", uid,
                            channel.name(), e.getMessage());
                    imapCommand(session, tag++, "UID STORE " + uid + " +FLAGS (\\Seen)");
                    continue;
                }
                String senderId = email.address();
                if (senderId == null || senderId.isBlank()) {
                    senderId = "unbekannt";
                }
                String senderName = email.name();
                if (senderName == null || senderName.isBlank()) {
                    senderName = senderId;
                }
                ChannelMessage inbound = ChannelMessage.inbound(channel.id(), String.valueOf(uid),
                        email.body() == null ? "" : email.body(), senderId, senderName, senderId, null);
                handler.onMessage(inbound);
                imapCommand(session, tag++, "UID STORE " + uid + " +FLAGS (\\Seen)");
            }
            imapCommand(session, tag++, "LOGOUT");
        } finally {
            currentSession = null;
            session.close();
        }
    }

    private static String imapCommand(ImapSession session, int tag, String command) throws IOException {
        session.writeLine("a" + tag + " " + command);
        String line;
        while ((line = session.readLine()) != null) {
            if (line.startsWith("a" + tag + " ")) {
                if (line.startsWith("a" + tag + " NO") || line.startsWith("a" + tag + " BAD")) {
                    throw new IOException("IMAP-Fehler (" + command + "): " + line);
                }
                return line;
            }
        }
        throw new IOException("IMAP-Verbindung vor Abschluss des Kommandos beendet: " + command);
    }

    private static List<Long> searchUnseen(ImapSession session, int tag) throws IOException {
        session.writeLine("a" + tag + " UID SEARCH UNSEEN");
        List<Long> uids = new ArrayList<>();
        String line;
        while ((line = session.readLine()) != null) {
            if (line.startsWith("* SEARCH")) {
                for (String token : line.substring("* SEARCH".length()).trim().split("\\s+")) {
                    if (token.isEmpty()) {
                        continue;
                    }
                    try {
                        uids.add(Long.parseLong(token));
                    } catch (NumberFormatException ignored) {
                        // Nicht-numerische Tokens ignorieren
                    }
                }
            } else if (line.startsWith("a" + tag + " ")) {
                if (line.startsWith("a" + tag + " NO") || line.startsWith("a" + tag + " BAD")) {
                    throw new IOException("IMAP-Suchfehler: " + line);
                }
                return uids;
            }
        }
        throw new IOException("IMAP-Verbindung vor Suchabschluss beendet.");
    }

    private static byte[] fetchRaw(ImapSession session, int tag, long uid) throws IOException {
        session.writeLine("a" + tag + " UID FETCH " + uid + " (RFC822)");
        byte[] literal = null;
        String line;
        while ((line = session.readLine()) != null) {
            if (line.startsWith("a" + tag + " ")) {
                if (line.startsWith("a" + tag + " NO") || line.startsWith("a" + tag + " BAD")) {
                    throw new IOException("IMAP-Fetch-Fehler: " + line);
                }
                return literal;
            }
            Matcher m = LITERAL.matcher(line);
            if (m.find()) {
                literal = session.readRaw(Integer.parseInt(m.group(1)));
            }
        }
        throw new IOException("IMAP-Verbindung vor Fetch-Abschluss beendet.");
    }

    // --- MIME-Parsing ---

    static ParsedEmail parseRaw(byte[] raw) throws ChannelException {
        try {
            Properties props = new Properties();
            Session session = Session.getInstance(props);
            MimeMessage msg = new MimeMessage(session, new ByteArrayInputStream(raw));
            String address = null;
            String name = null;
            Address[] from = msg.getFrom();
            if (from != null && from.length > 0) {
                if (from[0] instanceof InternetAddress ia) {
                    address = ia.getAddress();
                    name = ia.getPersonal();
                } else {
                    address = from[0].toString();
                }
            }
            String body = extractText(msg.getContent());
            return new ParsedEmail(address, name, body);
        } catch (MessagingException | IOException e) {
            throw new ChannelException("E-Mail konnte nicht geparst werden: " + e.getMessage(), e);
        }
    }

    private static String extractText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Multipart mp) {
            StringBuilder plain = new StringBuilder();
            StringBuilder htmlFallback = new StringBuilder();
            extractFromMultipart(mp, plain, htmlFallback);
            if (plain.length() > 0) {
                return plain.toString();
            }
            return htmlFallback.toString();
        }
        return content.toString();
    }

    private static void extractFromMultipart(Multipart mp, StringBuilder plain, StringBuilder htmlFallback) {
        try {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                Object content = part.getContent();
                if (part.isMimeType("text/plain")) {
                    plain.append(content).append('\n');
                } else if (part.isMimeType("multipart/*")) {
                    extractFromMultipart((Multipart) content, plain, htmlFallback);
                } else if (part.isMimeType("text/html") && htmlFallback.length() == 0) {
                    htmlFallback.append(content);
                } else if (htmlFallback.length() == 0 && content != null) {
                    htmlFallback.append(content).append('\n');
                }
            }
        } catch (MessagingException | IOException ignored) {
            // Best-effort: unparsebare Teile ueberspringen
        }
    }

    // --- Helfer ---

    private String resolveRecipient(ChannelMessage message) {
        if (message.threadId() != null && !message.threadId().isBlank()) {
            return message.threadId();
        }
        if (message.senderId() != null && !message.senderId().isBlank()) {
            return message.senderId();
        }
        return null;
    }

    private static boolean has(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v != null && !String.valueOf(v).isBlank();
    }

    private static String require(Channel channel, String key) {
        Object v = channel.config().get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("E-Mail-Konfiguration '" + key + "' fehlt.");
        }
        return String.valueOf(v);
    }

    private static String configString(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intConfig(Channel channel, String key, int fallback) {
        Object v = channel.config().get(key);
        if (v instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        if (v instanceof String s) {
            try {
                return Math.max(0, Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // Fall durch
            }
        }
        return fallback;
    }

    private static boolean boolConfig(Channel channel, String key) {
        Object v = channel.config().get(key);
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    private static void sleepQuietly(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Socket-Abstraktionen für Testbarkeit ---

    /**
     * Stellt eine SMTP-Verbindung her. Standard: direkte TCP-Socket-Verbindung (optional TLS,
     * RFC 5321). In Tests zeigen die Host-/Port-Konfiguration auf einen lokalen Fake-Server.
     */
    @FunctionalInterface
    interface SmtpConnector {
        SmtpSession connect(String server, int port, boolean tls) throws IOException;
    }

    /**
     * Stellt eine IMAP-Verbindung her. Standard: direkte TCP-Socket-Verbindung (optional TLS,
     * RFC 3501). In Tests zeigen die Host-/Port-Konfiguration auf einen lokalen Fake-Server.
     */
    @FunctionalInterface
    interface ImapConnector {
        ImapSession connect(String server, int port, boolean tls) throws IOException;
    }

    /** Parst eine rohe RFC-822/MIME-Nachricht in ein {@link ParsedEmail}. */
    @FunctionalInterface
    interface MessageParser {
        ParsedEmail parse(byte[] raw) throws ChannelException;
    }

    /** Ergebnis des MIME-Parsings. */
    record ParsedEmail(String address, String name, String body) {
    }

    /** SMTP-Session (zeilenbasiert, CRLF). */
    interface SmtpSession {
        String readLine() throws IOException;

        void writeLine(String line) throws IOException;

        void close();
    }

    /** IMAP-Session (zeilenbasiert, CRLF, mit rohen Literal-Bytes). */
    interface ImapSession {
        String readLine() throws IOException;

        void writeLine(String line) throws IOException;

        byte[] readRaw(int n) throws IOException;

        void close();
    }

    private static SmtpSession connectSmtp(String server, int port, boolean tls) throws IOException {
        return new SocketSmtpSession(connect(server, port, tls));
    }

    private static ImapSession connectImap(String server, int port, boolean tls) throws IOException {
        return new SocketImapSession(connect(server, port, tls));
    }

    private static Socket connect(String server, int port, boolean tls) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(server, port), 15_000);
        if (tls) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, server, port, true);
            ssl.startHandshake();
            return ssl;
        }
        return socket;
    }

    static final class SocketSmtpSession implements SmtpSession {
        private final Socket socket;

        SocketSmtpSession(Socket socket) {
            this.socket = socket;
        }

        @Override
        public String readLine() throws IOException {
            return readTextLine(socket.getInputStream());
        }

        @Override
        public void writeLine(String line) throws IOException {
            writeTextLine(socket.getOutputStream(), line);
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    static final class SocketImapSession implements ImapSession {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        SocketImapSession(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        @Override
        public String readLine() throws IOException {
            return readTextLine(in);
        }

        @Override
        public void writeLine(String line) throws IOException {
            writeTextLine(out, line);
        }

        @Override
        public byte[] readRaw(int n) throws IOException {
            byte[] buffer = new byte[n];
            int offset = 0;
            while (offset < n) {
                int read = in.read(buffer, offset, n - offset);
                if (read < 0) {
                    throw new IOException("Vorzeitiges Verbindungsende beim Lesen der Literal-Daten.");
                }
                offset += read;
            }
            return buffer;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readTextLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        int c;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return any ? sb.toString() : null;
    }

    private static void writeTextLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}