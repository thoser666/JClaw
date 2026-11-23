package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MediaAttachment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramMediaAdapterTest {

    private Channel channel(String baseUrl) {
        return new Channel("t1", "Telegram", ChannelType.TELEGRAM, true,
                Map.of("token", "test-token", "baseUrl", baseUrl), Instant.now(), Instant.now());
    }

    private ChannelMessage outbound() {
        return ChannelMessage.outbound("t1", "Sprachnachricht", "42", null);
    }

    private HttpServer server(String responseBody, AtomicReference<String> path,
                              AtomicReference<String> contentType) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    @Test
    void voiceNoteUsesSendVoiceEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("{\"ok\":true,\"result\":{\"message_id\":555}}", path, contentType);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();

        ChannelMessage sent = adapter.sendMedia(channel("http://127.0.0.1:" + server.getAddress().getPort()),
                outbound(),
                new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/ogg", "note.ogg", new byte[] {1, 2, 3}));

        assertThat(path.get()).isEqualTo("/bottest-token/sendVoice");
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        assertThat(sent.externalId()).isEqualTo("555");
        server.stop(0);
    }

    @Test
    void regularAudioUsesSendAudioEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("{\"ok\":true,\"result\":{\"message_id\":777}}", path, contentType);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();

        adapter.sendMedia(channel("http://127.0.0.1:" + server.getAddress().getPort()),
                outbound(),
                new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/mpeg", "song.mp3", new byte[] {9}));

        assertThat(path.get()).isEqualTo("/bottest-token/sendAudio");
        server.stop(0);
    }

    @Test
    void imageUsesSendPhotoEndpoint() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("{\"ok\":true,\"result\":{\"message_id\":1}}", path, contentType);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();

        adapter.sendMedia(channel("http://127.0.0.1:" + server.getAddress().getPort()),
                outbound(),
                new MediaAttachment(MediaAttachment.MediaKind.IMAGE, "image/png", "pic.png", new byte[] {4}));

        assertThat(path.get()).isEqualTo("/bottest-token/sendPhoto");
        server.stop(0);
    }

    @Test
    void rejectsEmptyAttachmentAndMissingChatId() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();

        assertThatThrownBy(() -> adapter.sendMedia(channel("http://localhost:1"), outbound(),
                new MediaAttachment(MediaAttachment.MediaKind.AUDIO, "audio/ogg", "a.ogg", new byte[0])))
                .isInstanceOf(biz.brumm.domain.port.out.ChannelAdapter.ChannelException.class)
                .hasMessageContaining("leer");

        assertThatThrownBy(() -> adapter.sendMedia(channel("http://localhost:1"),
                ChannelMessage.outbound("t1", "x", null, null),
                new MediaAttachment(MediaAttachment.MediaKind.DOCUMENT, "text/plain", "a.txt", new byte[] {1})))
                .isInstanceOf(biz.brumm.domain.port.out.ChannelAdapter.ChannelException.class)
                .hasMessageContaining("chatId");
    }

    @Test
    void apiErrorIsReported() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server("{\"ok\":false,\"description\":\"bad request\"}", path, contentType);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter();

        assertThatThrownBy(() -> adapter.sendMedia(
                channel("http://127.0.0.1:" + server.getAddress().getPort()), outbound(),
                new MediaAttachment(MediaAttachment.MediaKind.DOCUMENT, "text/plain", "a.txt", new byte[] {1})))
                .isInstanceOf(biz.brumm.domain.port.out.ChannelAdapter.ChannelException.class)
                .hasMessageContaining("bad request");

        server.stop(0);
    }
}