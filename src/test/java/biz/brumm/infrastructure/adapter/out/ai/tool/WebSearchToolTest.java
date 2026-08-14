package biz.brumm.infrastructure.adapter.out.ai.tool;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchToolTest {

    @Test
    void webSearchFormatsAbstractAndRelatedTopics() throws Exception {
        String json = """
                {
                  "AbstractText": "Java ist eine Programmiersprache.",
                  "AbstractURL": "https://de.wikipedia.org/wiki/Java",
                  "RelatedTopics": [
                    {"Text": "OpenJDK - Implementierung", "FirstURL": "https://openjdk.org"},
                    {"Name": "Mehr", "Topics": [
                      {"Text": "Spring Framework", "FirstURL": "https://spring.io"}
                    ]}
                  ]
                }
                """;
        withServer(exchange -> respond(exchange, 200, json), base -> {
            WebSearchTool tool = new WebSearchTool(base, 5, Duration.ofSeconds(5));

            String result = tool.webSearch("java");

            assertThat(result)
                    .contains("Ergebnisse für 'java':")
                    .contains("Java ist eine Programmiersprache. (https://de.wikipedia.org/wiki/Java)")
                    .contains("OpenJDK - Implementierung (https://openjdk.org)")
                    .contains("Spring Framework (https://spring.io)");
        });
    }

    @Test
    void webSearchCapsResultCount() throws Exception {
        StringBuilder topics = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (topics.length() > 0) {
                topics.append(",");
            }
            topics.append("{\"Text\": \"Treffer ").append(i).append("\", \"FirstURL\": \"https://x.example/")
                    .append(i).append("\"}");
        }
        String json = "{\"RelatedTopics\": [" + topics + "]}";
        withServer(exchange -> respond(exchange, 200, json), base -> {
            WebSearchTool tool = new WebSearchTool(base, 3, Duration.ofSeconds(5));

            String result = tool.webSearch("test");

            assertThat(result.lines().filter(line -> line.startsWith("- ")).count()).isEqualTo(3);
        });
    }

    @Test
    void webSearchReturnsNoHitsMessageForEmptyResult() throws Exception {
        String json = "{\"RelatedTopics\": [], \"AbstractText\": \"\"}";
        withServer(exchange -> respond(exchange, 200, json), base -> {
            WebSearchTool tool = new WebSearchTool(base, 5, Duration.ofSeconds(5));

            assertThat(tool.webSearch("nix")).contains("keine Treffer");
        });
    }

    @Test
    void webSearchReportsInvalidJsonResponse() throws Exception {
        withServer(exchange -> respond(exchange, 200, "kein json {"), base -> {
            WebSearchTool tool = new WebSearchTool(base, 5, Duration.ofSeconds(5));

            assertThat(tool.webSearch("test")).contains("Ungültige Such-Antwort");
        });
    }

    @Test
    void webSearchRejectsBlankQuery() {
        WebSearchTool tool = new WebSearchTool("https://api.duckduckgo.com", 5, Duration.ofSeconds(5));

        assertThat(tool.webSearch("  ")).contains("Suchanfrage darf nicht leer sein");
    }

    @Test
    void webSearchReportsNonSuccessStatus() throws Exception {
        withServer(exchange -> respond(exchange, 500, "kaputt"), base -> {
            WebSearchTool tool = new WebSearchTool(base, 5, Duration.ofSeconds(5));

            assertThat(tool.webSearch("test")).contains("HTTP-Status 500");
        });
    }

    @Test
    void buildSearchUriEncodesQueryAndSetsFormat() {
        WebSearchTool tool = new WebSearchTool("https://api.duckduckgo.com", 5, Duration.ofSeconds(5));

        assertThat(tool.buildSearchUri("a b?c"))
                .hasToString("https://api.duckduckgo.com?q=a+b%3Fc&format=json&no_html=1&skip_disambig=1");
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
}
