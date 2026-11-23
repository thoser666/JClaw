package biz.brumm.infrastructure.adapter.out.channel;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MediaAttachment;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordMediaAdapterTest {

    private Channel channel(String baseUrl) {
        return new Channel("d1", "Discord", ChannelType.DISCORD, true,
                Map.of("token", "test-token", "baseUrl", baseUrl), Instant.now(), Instant.now());
    }

    private ChannelMessage outbound(String channelId) {
        return ChannelMessage.outbound("d1", "Bildunterschrift", channelId, null);
    }

    private HttpServer server(String responseBody, AtomicReference<String> path,
                              AtomicReference<String> auth) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/channels", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
    void sendsMultipartUploadWithAuthAndReturnsExternalId() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = server("{\"id\":\"9999\"}", path, auth);
        DiscordChannelAdapter adapter = new DiscordChannelAdapter();

        ChannelMessage sent = adapter.sendMedia(
                channel("http://127.0.0.1:" + server.getAddress().getPort()),
                outbound("123456789"),
                new MediaAttachment(MediaAttachment.MediaKind.IMAGE, "image/png", "pic.png", new byte[] {7}));

        assertThat(path.get()).isEqualTo("/channels/123456789/messages");
        assertThat(auth.get()).isEqualTo("Bot test-token");
        assertThat(sent.externalId()).isEqualTo("9999");
        server.stop(0);
    }

    @Test
    void rejectsEmptyAttachmentAndMissingChannelId() {
        DiscordChannelAdapter adapter = new DiscordChannelAdapter();

        assertThatThrownBy(() -> adapter.sendMedia(channel("http://localhost:1"), outbound("1"),
                new MediaAttachment(MediaAttachment.MediaKind.IMAGE, "image/png", "a.png", new byte[0])))
                .isInstanceOf(biz.brumm.domain.port.out.ChannelAdapter.ChannelException.class)
                .hasMessageContaining("leer");

        assertThatThrownBy(() -> adapter.sendMedia(channel("http://localhost:1"),
                ChannelMessage.outbound("d1", "x", null, null),
                new MediaAttachment(MediaAttachment.MediaKind.DOCUMENT, "text/plain", "a.txt", new byte[] {1})))
                .isInstanceOf(biz.brumm.domain.port.out.ChannelAdapter.ChannelException.class)
                .hasMessageContaining("Discord-channel");
    }
}