package biz.brumm.infrastructure.adapter.out.persistence;

import biz.brumm.config.MemoryVaultProperties;
import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.port.out.MemoryVaultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link MemoryVaultStore} auf Basis von Markdown-Dateien mit YAML-Frontmatter
 * (Open Memory Vault, P4-02).
 *
 * <p>Jedes Dokument wird als {@code <slug>.md} im konfigurierten Vault-Ordner
 * abgelegt und ist damit menschenlesbar und editierbar (z. B. via Tolaria oder
 * Obsidian). H2 bleibt Quelle der Wahrheit — die Dateien sind ein idempotenter,
 * lesbarer Auszug. Der Adapter ist per {@code jclaw.memory.vault.enabled=true}
 * aktiviert (Deny-by-Default).</p>
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.memory.vault", name = "enabled", havingValue = "true")
public class MarkdownMemoryVault implements MemoryVaultStore {

    private static final Logger log = LoggerFactory.getLogger(MarkdownMemoryVault.class);

    private final Path vaultDir;

    public MarkdownMemoryVault(MemoryVaultProperties properties) {
        this.vaultDir = Path.of(properties.dir());
    }

    @Override
    public void store(MemoryDocument document) {
        try {
            Files.createDirectories(vaultDir);
            Path file = vaultDir.resolve(slugify(document.conversationId()) + ".md");
            Files.writeString(file, render(document), StandardCharsets.UTF_8);
            log.info("Memory-Vault: {} nach {} materialisiert.", document.conversationId(), file);
        } catch (IOException e) {
            throw new IllegalStateException("Memory-Vault konnte Dokument nicht schreiben: " + e.getMessage(), e);
        }
    }

    @Override
    public List<MemoryDocument> list() {
        if (!Files.isDirectory(vaultDir)) {
            return List.of();
        }
        List<MemoryDocument> documents = new ArrayList<>();
        try (Stream<Path> stream = Files.list(vaultDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                readDocument(file).ifPresent(documents::add);
            }
        } catch (IOException e) {
            log.warn("Memory-Vault konnte Verzeichnis nicht lesen: {}", e.getMessage());
        }
        return documents;
    }

    /**
     * Liest ein Markdown-Vault-Dokument als {@link MemoryDocument}.
     * Wird u. a. vom Read-Back/Watcher genutzt, um User-Änderungen zu erfassen.
     *
     * @param file zu lesende {@code .md}-Datei
     * @return das Dokument, oder leer, wenn die Datei nicht lesbar/verarbeitbar ist
     */
    public static Optional<MemoryDocument> readDocument(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> frontmatter = parseFrontmatter(text);
            String content = stripFrontmatter(text);
            String conversationId = String.valueOf(frontmatter.getOrDefault("conversationId", ""));
            String title = String.valueOf(frontmatter.getOrDefault("title", ""));
            Instant createdAt = parseInstant(String.valueOf(frontmatter.getOrDefault("createdAt", "")));
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) frontmatter.getOrDefault("tags", List.of());
            return Optional.of(new MemoryDocument(conversationId, title, createdAt, tags, content));
        } catch (IOException | RuntimeException e) {
            log.warn("Memory-Vault konnte {} nicht lesen: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Formatiert eine Konversation als Markdown-Body (je Nachricht
     * {@code **ROLLE**\n\ntext}). Wird sowohl beim Schreiben als auch beim
     * Lesen (Read-Back) verwendet, damit das Format symmetrisch bleibt.
     */
    public static String renderMessages(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ConversationMessage message : messages) {
            sb.append("**").append(message.role()).append("**\n\n")
                    .append(message.text() == null ? "" : message.text())
                    .append("\n\n");
        }
        return sb.toString();
    }

    private static final Pattern MESSAGE_BLOCK = Pattern.compile(
            "(?m)^\\*\\*(?<role>[A-Z0-9_]+)\\*\\*\\s*\\n\\n(?<text>.*?)(?=\\n\\n\\*\\*[A-Z0-9_]+\\*\\*\\s*\\n|\\z)",
            Pattern.DOTALL);

    /**
     * Extrahiert die Konversationsnachrichten aus einem Markdown-Body (Format von
     * {@link #renderMessages}). Dient dem bidirektionalen Sync von User-Änderungen
     * im Vault zurück in die Konversation.
     */
    public static List<ConversationMessage> parseMessages(String markdownContent) {
        if (markdownContent == null || markdownContent.isBlank()) {
            return List.of();
        }
        List<ConversationMessage> messages = new ArrayList<>();
        Matcher matcher = MESSAGE_BLOCK.matcher(markdownContent);
        while (matcher.find()) {
            String role = matcher.group("role");
            String text = matcher.group("text") == null ? "" : matcher.group("text").trim();
            if (!role.isBlank()) {
                messages.add(new ConversationMessage(role, text));
            }
        }
        return messages;
    }

    static String render(MemoryDocument document) {
        var frontmatter = new LinkedHashMap<String, Object>();
        frontmatter.put("conversationId", document.conversationId());
        frontmatter.put("title", document.title());
        frontmatter.put("createdAt", document.createdAt() == null ? "" : document.createdAt().toString());
        frontmatter.put("tags", document.tags());

        StringBuilder sb = new StringBuilder("---\n");
        sb.append(toYaml(frontmatter));
        sb.append("---\n\n# ").append(document.title() == null ? "" : document.title()).append("\n\n");
        sb.append(document.content() == null ? "" : document.content().trim()).append('\n');
        return sb.toString();
    }

    private static String toYaml(Map<String, Object> map) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(map);
    }

    private static Map<String, Object> parseFrontmatter(String text) {
        if (!text.startsWith("---")) {
            return Map.of();
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return Map.of();
        }
        String body = text.substring(3, end);
        Object parsed = new Yaml().load(body);
        return parsed instanceof Map<?, ?> map ? toStringMap(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static String stripFrontmatter(String text) {
        if (!text.startsWith("---")) {
            return text;
        }
        int end = text.indexOf("\n---", 3);
        if (end < 0) {
            return text;
        }
        String body = text.substring(end + 4);
        // Heading-Zeile nach dem Frontmatter entfernen
        return body.replaceFirst("(?m)^#\\s.*\\n\\n?", "").trim();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    static String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "memory";
        }
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "memory" : slug;
    }
}
