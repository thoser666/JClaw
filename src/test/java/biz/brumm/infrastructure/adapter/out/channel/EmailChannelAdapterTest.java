package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.infrastructure.adapter.out.channel.EmailChannelAdapter.ParsedEmail;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailChannelAdapterTest {

    private Channel channel(String server, int smtpPort, int imapPort, Map<String, Object> extra) {
        Map<String, Object> cfg = new java.util.HashMap<>();
        cfg.put("server", server);
        cfg.put("smtpPort", smtpPort);
        cfg.put("imapPort", imapPort);
        cfg.put("username", "jclaw@example.org");
        cfg.put("password", "geheim");
        cfg.putAll(extra);
        return new Channel("mail1", "Mail Test", ChannelType.EMAIL, true, cfg, Instant.now(), Instant.now());
    }

    // --- Grundlagen ---

    @Test
    void channelTypeIsEmail() {
        assertThat(new EmailChannelAdapter().channelType()).isEqualTo(ChannelType.EMAIL);
    }

    @Test
    void availabilityRequiresEnabledServerAndCredentials() {
        EmailChannelAdapter adapter = new EmailChannelAdapter();
        Channel ok = new Channel("c", "n", ChannelType.EMAIL, true, Map.of(
                "server", "mail.example.org", "username", "u", "password", "p"),
                Instant.now(), Instant.now());
        Channel noPassword = new Channel("c", "n", ChannelType.EMAIL, true, Map.of(
                "server", "mail.example.org", "username", "u"),
                Instant.now(), Instant.now());
        Channel noServer = new Channel("c", "n", ChannelType.EMAIL, true, Map.of(
                "username", "u", "password", "p"),
                Instant.now(), Instant.now());
        Channel disabled = new Channel("c", "n", ChannelType.EMAIL, false, Map.of(
                "server", "x", "username", "u", "password", "p"),
                Instant.now(), Instant.now());

        assertThat(adapter.isAvailable(ok)).isTrue();
        assertThat(adapter.isAvailable(noPassword)).isFalse();
        assertThat(adapter.isAvailable(noServer)).isFalse();
        assertThat(adapter.isAvailable(disabled)).isFalse();
        assertThat(adapter.isAvailable(null)).isFalse();
    }

    // --- Senden (SMTP) ---

    @Test
    void sendRunsSmtpDialogAndDeliversData() throws Exception {
        FakeSmtpServer server = new FakeSmtpServer();
        server.start();
        try {
            EmailChannelAdapter adapter = new EmailChannelAdapter();
            Channel ch = channel("127.0.0.1", server.getPort(), 1, Map.of());

            ChannelMessage sent = adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Zeile eins\n.versteckte Zeile", "max@example.com", null));

            assertThat(sent.direction()).isEqualTo(MessageDirection.OUTBOUND);
            assertThat(sent.threadId()).isEqualTo("max@example.com");
            assertThat(server.commands).contains("MAIL FROM:<jclaw@example.org>",
                    "RCPT TO:<max@example.com>", "DATA", "QUIT");
            assertThat(server.dataLines).contains("From: <jclaw@example.org>",
                    "To: <max@example.com>", "Subject: JClaw", "Zeile eins", "..versteckte Zeile", ".");
            assertThat(server.seenEhlo).isTrue();
            assertThat(server.seenQuitReply).isTrue();
        } finally {
            server.stop();
        }
    }

    @Test
    void sendFallsBackToSenderIdAsRecipient() throws Exception {
        FakeSmtpServer server = new FakeSmtpServer();
        server.start();
        try {
            EmailChannelAdapter adapter = new EmailChannelAdapter();
            Channel ch = channel("127.0.0.1", server.getPort(), 1, Map.of());
            ChannelMessage outbound = new ChannelMessage("m1", ch.id(), null, MessageDirection.OUTBOUND,
                    "Hi", "empfaenger@example.org", "Empfaenger", null, null, Instant.now());

            adapter.send(ch, outbound);

            assertThat(server.commands).contains("RCPT TO:<empfaenger@example.org>");
        } finally {
            server.stop();
        }
    }

    @Test
    void sendReportsSmtpError() throws Exception {
        FakeSmtpServer server = new FakeSmtpServer();
        server.rejectRcpt = true;
        server.start();
        try {
            EmailChannelAdapter adapter = new EmailChannelAdapter();
            Channel ch = channel("127.0.0.1", server.getPort(), 1, Map.of());

            assertThatThrownBy(() -> adapter.send(ch,
                    ChannelMessage.outbound(ch.id(), "Hi", "max@example.com", null)))
                    .isInstanceOf(ChannelAdapter.ChannelException.class)
                    .hasMessageContaining("SMTP");
        } finally {
            server.stop();
        }
    }

    @Test
    void sendWithoutRecipientThrows() throws Exception {
        EmailChannelAdapter adapter = new EmailChannelAdapter();
        Channel ch = channel("127.0.0.1", 1, 1, Map.of());
        ChannelMessage outbound = new ChannelMessage("m1", ch.id(), null, MessageDirection.OUTBOUND,
                "text", null, null, null, null, Instant.now());

        assertThatThrownBy(() -> adapter.send(ch, outbound))
                .isInstanceOf(ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Empfaenger");
    }

    @Test
    void sendWithoutServerThrows() {
        EmailChannelAdapter adapter = new EmailChannelAdapter();
        Channel ch = new Channel("c", "n", ChannelType.EMAIL, true,
                Map.of("username", "u", "password", "p"), Instant.now(), Instant.now());

        assertThatThrownBy(() -> adapter.send(ch,
                ChannelMessage.outbound(ch.id(), "Hi", "max@example.com", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server");
    }

    // --- Empfang (IMAP-Polling) ---

    @Test
    void startReceivingPollsImapAndDeliversMessages() throws Exception {
        FakeImapServer server = new FakeImapServer();
        server.addMessage(101L, rawPlain());
        server.addMessage(102L, rawMultipart());
        server.start();
        try {
            EmailChannelAdapter adapter = new EmailChannelAdapter();
            Channel ch = channel("127.0.0.1", 1, server.getPort(), Map.of());
            List<ChannelMessage> received = new CopyOnWriteArrayList<>();

            adapter.startReceiving(ch, received::add);
            waitFor(() -> received.size() == 2);
            adapter.stopReceiving(ch);

            ChannelMessage first = received.get(0);
            assertThat(first.content()).contains("Hi, das ist ein Test");
            assertThat(first.senderId()).isEqualTo("max@example.com");
            assertThat(first.senderName()).isEqualTo("Max Mustermann");
            assertThat(first.threadId()).isEqualTo("max@example.com");
            assertThat(first.externalId()).isEqualTo("101");
            assertThat(first.direction()).isEqualTo(MessageDirection.INBOUND);

            ChannelMessage second = received.get(1);
            assertThat(second.content()).contains("Nur Klartext bitte");
            assertThat(second.content()).doesNotContain("html");
            assertThat(second.content()).doesNotContain("<b>");
            assertThat(second.externalId()).isEqualTo("102");
        } finally {
            server.stop();
        }
    }

    @Test
    void startReceivingWithoutServerThrows() {
        EmailChannelAdapter adapter = new EmailChannelAdapter();
        Channel ch = new Channel("c", "n", ChannelType.EMAIL, true,
                Map.of("username", "u", "password", "p"), Instant.now(), Instant.now());

        assertThatThrownBy(() -> adapter.startReceiving(ch, m -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server");
    }

    // --- MIME-Parsing ---

    @Test
    void parsesPlainTextMessage() throws Exception {
        ParsedEmail email = EmailChannelAdapter.parseRaw(rawPlain());

        assertThat(email.address()).isEqualTo("max@example.com");
        assertThat(email.name()).isEqualTo("Max Mustermann");
        assertThat(email.body()).contains("Hi, das ist ein Test");
    }

    @Test
    void parsesMultipartPreferringPlainText() throws Exception {
        ParsedEmail email = EmailChannelAdapter.parseRaw(rawMultipart());

        assertThat(email.body()).contains("Nur Klartext bitte");
        assertThat(email.body()).doesNotContain("<b>");
    }

    @Test
    void parsesMessageWithoutFromHeader() throws Exception {
        String raw = "Subject: Kein Absender\n\nNur der Text.";
        ParsedEmail email = EmailChannelAdapter.parseRaw(raw.getBytes(StandardCharsets.UTF_8));

        assertThat(email.address()).isNull();
        assertThat(email.body()).contains("Nur der Text");
    }

    // --- Roh-Nachrichten für die Fake-IMAP-Server ---

    private static byte[] rawPlain() {
        return ("From: Max Mustermann <max@example.com>\r\n"
                + "Subject: Hallo JClaw\r\n"
                + "Date: Thu, 03 Sep 2026 10:00:00 +0000\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Hi, das ist ein Test.").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] rawMultipart() {
        return ("MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"JCLAW-BOUNDARY\"\r\n"
                + "\r\n"
                + "--JCLAW-BOUNDARY\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Nur Klartext bitte\r\n"
                + "--JCLAW-BOUNDARY\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "\r\n"
                + "<html><body><b>Nur</b> HTML</body></html>\r\n"
                + "--JCLAW-BOUNDARY--").getBytes(StandardCharsets.UTF_8);
    }

    // --- Helfer ---

    private static void waitFor(CheckedSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.get()).isTrue();
    }

    @FunctionalInterface
    interface CheckedSupplier {
        boolean get() throws Exception;
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.length() == 0 && c == -1 ? null : sb.toString();
    }

    private static void writeRaw(OutputStream out, String text) throws Exception {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Minimaler Fake-SMTP-Server (RFC 5321): nimmt die Befehle auf und antwortet mit 2xx/5xx. */
    private static final class FakeSmtpServer {
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<String> dataLines = new CopyOnWriteArrayList<>();
        private volatile boolean rejectRcpt;
        private volatile boolean seenEhlo;
        private volatile boolean seenQuitReply;
        private final AtomicReference<ServerSocket> socketRef = new AtomicReference<>();
        private final AtomicInteger port = new AtomicInteger(0);
        private volatile Thread thread;

        int getPort() {
            return port.get();
        }

        void start() throws Exception {
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            socketRef.set(ss);
            port.set(ss.getLocalPort());
            thread = new Thread(() -> serve(ss), "fake-smtp");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            ServerSocket ss = socketRef.getAndSet(null);
            if (ss != null) {
                try {
                    ss.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void serve(ServerSocket ss) {
            try (Socket socket = ss.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                writeRaw(out, "220 fake ESMTP ready\r\n");
                boolean inData = false;
                String line;
                while ((line = readLine(in)) != null) {
                    if (inData) {
                        if (line.equals(".")) {
                            dataLines.add(line);
                            inData = false;
                            writeRaw(out, "250 OK\r\n");
                        } else {
                            dataLines.add(line);
                        }
                        continue;
                    }
                    commands.add(line);
                    if (line.startsWith("EHLO")) {
                        seenEhlo = true;
                        writeRaw(out, "250 OK\r\n");
                    } else if (line.startsWith("MAIL FROM")) {
                        writeRaw(out, "250 OK\r\n");
                    } else if (line.startsWith("RCPT TO")) {
                        if (rejectRcpt) {
                            writeRaw(out, "550 No such user\r\n");
                        } else {
                            writeRaw(out, "250 OK\r\n");
                        }
                    } else if (line.equals("DATA")) {
                        inData = true;
                        writeRaw(out, "354 End data with <CR><LF>.<CR><LF>\r\n");
                    } else if (line.equals("QUIT")) {
                        writeRaw(out, "221 Bye\r\n");
                        seenQuitReply = true;
                        break;
                    } else {
                        writeRaw(out, "250 OK\r\n");
                    }
                }
            } catch (Exception ignored) {
                // Server nur für Tests; Verbindungsabbruch ist ok.
            }
        }
    }

    /** Minimaler Fake-IMAP-Server (RFC 3501): LOGIN/SELECT/UID SEARCH/UID FETCH/UID STORE/LOGOUT. */
    private static final class FakeImapServer {
        private final List<MessageHolder> messages = new ArrayList<>();
        private final AtomicReference<ServerSocket> socketRef = new AtomicReference<>();
        private final AtomicInteger port = new AtomicInteger(0);
        private volatile Thread thread;

        void addMessage(long uid, byte[] raw) {
            messages.add(new MessageHolder(uid, raw));
        }

        int getPort() {
            return port.get();
        }

        void start() throws Exception {
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            socketRef.set(ss);
            port.set(ss.getLocalPort());
            thread = new Thread(() -> serve(ss), "fake-imap");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            ServerSocket ss = socketRef.getAndSet(null);
            if (ss != null) {
                try {
                    ss.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void serve(ServerSocket ss) {
            try (Socket socket = ss.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                String line;
                while ((line = readLine(in)) != null) {
                    String tag = line.substring(0, line.indexOf(' '));
                    String rest = line.substring(line.indexOf(' ') + 1);
                    if (rest.startsWith("LOGIN")) {
                        writeRaw(out, tag + " OK LOGIN completed\r\n");
                    } else if (rest.startsWith("SELECT")) {
                        writeRaw(out, "* 2 EXISTS\r\n");
                        writeRaw(out, "* FLAGS (\\Seen \\Answered)\r\n");
                        writeRaw(out, tag + " OK [READ-WRITE] SELECT completed\r\n");
                    } else if (rest.startsWith("UID SEARCH")) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("* SEARCH");
                        for (MessageHolder m : messages) {
                            sb.append(' ').append(m.uid);
                        }
                        sb.append("\r\n").append(tag).append(" OK SEARCH completed\r\n");
                        writeRaw(out, sb.toString());
                    } else if (rest.startsWith("UID FETCH")) {
                        String uidToken = rest.replaceAll(".*UID FETCH (\\d+).*", "$1");
                        sendFetch(out, tag, uidToken);
                    } else if (rest.startsWith("UID STORE")) {
                        writeRaw(out, "* 1 FETCH (FLAGS (\\Seen))\r\n");
                        writeRaw(out, tag + " OK STORE completed\r\n");
                    } else if (rest.startsWith("LOGOUT")) {
                        writeRaw(out, "* BYE fake imap server\r\n");
                        writeRaw(out, tag + " OK LOGOUT completed\r\n");
                        break;
                    } else {
                        writeRaw(out, tag + " BAD unknown command\r\n");
                    }
                }
            } catch (Exception ignored) {
                // Server nur für Tests; Verbindungsabbruch ist ok.
            }
        }

        private void sendFetch(OutputStream out, String tag, String uidToken) throws Exception {
            for (MessageHolder m : messages) {
                if (String.valueOf(m.uid).equals(uidToken)) {
                    out.write(("* 1 FETCH (UID " + m.uid + " RFC822 {"
                            + m.raw.length + "}\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(m.raw);
                    out.write(("\r\n)\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write((tag + " OK FETCH completed\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                }
            }
            writeRaw(out, tag + " NO no such message\r\n");
        }

        private record MessageHolder(long uid, byte[] raw) {
        }
    }
}