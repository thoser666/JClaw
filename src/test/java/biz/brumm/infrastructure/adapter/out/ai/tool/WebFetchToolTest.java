package biz.brumm.infrastructure.adapter.out.ai.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WebFetchToolTest {

    @Test
    void webFetchReturnsConvertedTextFromAllowedDomain() throws Exception {
        withServer(exchange -> respond(exchange, 200,
                "<html><head><title>Test</title></head><body><h1>Hallo</h1><p>Welt &amp; mehr</p></body></html>"),
                base -> {
                    WebFetchTool tool = new WebFetchTool(List.of("localhost"), Duration.ofSeconds(5), 10_000);

                    assertThat(tool.webFetch(base + "/doku"))
                            .contains("Hallo")
                            .contains("Welt & mehr")
                            .doesNotContain("<h1>");
                });
    }

    @Test
    void hostPolicyAcceptsExactAndSubdomains() {
        WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

        assertThat(tool.isAllowedHost("example.com")).isTrue();
        assertThat(tool.isAllowedHost("docs.example.com")).isTrue();
        assertThat(tool.isAllowedHost("api.DOCS.example.com")).isTrue();
    }

    @Test
    void hostPolicyRejectsForeignAndEvilSuffixHosts() {
        WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

        assertThat(tool.isAllowedHost("example.org")).isFalse();
        assertThat(tool.isAllowedHost("example.com.evil.org")).isFalse();
        assertThat(tool.isAllowedHost("notexample.com")).isFalse();
        assertThat(tool.isAllowedHost("evil-example.com")).isFalse();
    }

    @Test
    void webFetchRejectsDisallowedDomain() throws Exception {
        withServer(exchange -> respond(exchange, 200, "<html>geheim</html>"), base -> {
            WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

            assertThat(tool.webFetch(base + "/geheim"))
                    .contains("ist nicht erlaubt")
                    .contains("localhost");
        });
    }

    @Test
    void webFetchWithoutAllowedDomainsDeniesEverything() throws Exception {
        withServer(exchange -> respond(exchange, 200, "<html>geheim</html>"), base -> {
            WebFetchTool tool = new WebFetchTool(List.of(), Duration.ofSeconds(5), 10_000);

            assertThat(tool.webFetch(base + "/geheim")).contains("ist nicht erlaubt");
        });
    }

    @Test
    void webFetchRejectsNonHttpScheme() {
        WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

        assertThat(tool.webFetch("file:///etc/passwd")).contains("Nur http/https");
    }

    @Test
    void webFetchRejectsBlankUrl() {
        WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

        assertThat(tool.webFetch("   ")).contains("URL darf nicht leer sein");
    }

    @Test
    void webFetchRejectsMalformedUrl() {
        WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

        assertThat(tool.webFetch("http://exa mple.com")).contains("Ungültige URL");
    }

    @Test
    void webFetchRejectsUrlWithUserInfo() {
        WebFetchTool tool = new WebFetchTool(List.of("example.com"), Duration.ofSeconds(5), 10_000);

        assertThat(tool.webFetch("http://user:pass@example.com/")).contains("Ungültige URL");
    }

    @Test
    void webFetchTruncatesOversizedContent() throws Exception {
        String bigHtml = "<html><body>" + "x".repeat(5_000) + "</body></html>";
        withServer(exchange -> respond(exchange, 200, bigHtml), base -> {
            WebFetchTool tool = new WebFetchTool(List.of("localhost"), Duration.ofSeconds(5), 100);

            assertThat(tool.webFetch(base + "/gross")).contains("... (Inhalt gekuerzt)");
        });
    }

    @Test
    void webFetchReportsNonSuccessStatus() throws Exception {
        withServer(exchange -> respond(exchange, 404, "<html>fehlt</html>"), base -> {
            WebFetchTool tool = new WebFetchTool(List.of("localhost"), Duration.ofSeconds(5), 10_000);

            assertThat(tool.webFetch(base + "/fehlt")).contains("HTTP-Status 404");
        });
    }

    @Test
    void webFetchReportsTimeout() throws Exception {
        withServer(exchange -> sleep(3_000), base -> {
            WebFetchTool tool = new WebFetchTool(List.of("localhost"), Duration.ofMillis(300), 10_000);

            assertThat(tool.webFetch(base + "/langsam")).contains("Zeitlimit");
        });
    }

    @Test
    void webFetchReturnsEmptyMarkerForBlankResponse() throws Exception {
        withServer(exchange -> respond(exchange, 200, ""), base -> {
            WebFetchTool tool = new WebFetchTool(List.of("localhost"), Duration.ofSeconds(5), 10_000);

            assertThat(tool.webFetch(base + "/leer")).isEqualTo("(leere Antwort)");
        });
    }

    @Test
    void toTextStripsTagsAndDecodesEntities() {
        String html = "<html><body><h1>Titel</h1><p>a &amp; b &lt; c</p><script>evil();</script></body></html>";

        assertThat(WebFetchTool.toText(html))
                .contains("Titel")
                .contains("a & b < c")
                .doesNotContain("evil")
                .doesNotContain("<h1>");
    }

    @Test
    void toTextPreservesLineBreaks() {
        String html = "<p>Zeile eins</p><p>Zeile zwei</p>";

        assertThat(WebFetchTool.toText(html)).contains("Zeile eins\nZeile zwei");
    }

    @Test
    void webFetchUsesConfiguredHttpClient() throws Exception {
        withServer(exchange -> respond(exchange, 200, "<html>ok</html>"), base -> {
            WebFetchTool tool = new WebFetchTool(List.of("localhost"), Duration.ofSeconds(5), 10_000,
                    HttpClient.newHttpClient());

            assertThat(tool.webFetch(base + "/ok")).contains("ok");
        });
    }

    private void withServer(HttpHandler handler, Consumer<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();
        try {
            body.accept("http://localhost:" + server.getAddress().getPort());
        } finally {
            server.stop(0);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
