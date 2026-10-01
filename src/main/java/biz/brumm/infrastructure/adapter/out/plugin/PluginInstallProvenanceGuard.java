package biz.brumm.infrastructure.adapter.out.plugin;

import biz.brumm.config.PluginRuntimeProperties;
import biz.brumm.domain.model.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Plugin-Install-Provenance (P4-09, fail-closed): prüft <em>vor</em> dem Laden eines Plugins in
 * den Node-Sidecar (d.h. vor der Ausführung seines Codes über {@code plugin.load}), ob das
 * Plugin über eine nachvollziehbare Install-Provenance verfügt.
 *
 * <p>Spiegel der OpenClaw-Semantik (2026.6.34/8.1, {@code --force} für beliebige ausführbare
 * Quellen): Vertrauenswürdige Quellen ({@code bundled}, offizieller {@code catalog}) laden ohne
 * weitere Freigabe. Beliebige Quellen ({@code local}, {@code git}, {@code archive}, {@code npm},
 * {@code marketplace}) brauchen eine explizite Freigabe — entweder wird die Quelle selbst über
 * {@code jclaw.agent.plugins.runtime.trusted-sources} vertraut oder die Plugin-Id wird in
 * {@code jclaw.agent.plugins.runtime.allow} gepinnt (Spiegel zu OpenClaw {@code plugins.allow} /
 * {@code --force}).
 *
 * <p>Für ein Plugin ohne Install-Pfad-Provenance (fehlende {@code .jclaw/install.json}) ist das
 * Ausknöpf-Aus die Freigabe: die Id pinnt man in {@code allow} (OpenClaw: „pin the trusted id in
 * plugins.allow or reinstall the plugin from a trusted source"). Unlesbare oder unvollständige
 * Provenance (<em>provenance-invalid</em>: fehlendes/leeres {@code source}-Feld, kein JSON) wird
 * dagegen <strong>nie</strong> automatisch vertraut — nicht einmal über {@code allow}; das Plugin
 * muss aus vertrauenswürdiger Quelle neu installiert werden. Ein blockiertes Plugin bleibt
 * Control-Plane-only und wird nicht in den Sidecar geladen.
 */
public final class PluginInstallProvenanceGuard {

    private static final Logger log = LoggerFactory.getLogger(PluginInstallProvenanceGuard.class);

    /** Provenance-Datensatz pro Plugin-Ordner (wird bei der Installation geschrieben). */
    public static final String PROVENANCE_FILE = ".jclaw/install.json";

    /** Feld im Provenance-Datensatz: Install-Quelle des Plugins. */
    public static final String FIELD_SOURCE = "source";

    private final ObjectMapper objectMapper;
    private final Set<String> trustedSources;
    private final Set<String> allow;

    public PluginInstallProvenanceGuard(ObjectMapper objectMapper, PluginRuntimeProperties properties) {
        this.objectMapper = objectMapper;
        this.trustedSources = Set.copyOf(properties.trustedSources());
        this.allow = Set.copyOf(properties.allow());
    }

    /**
     * Prüft, ob das Plugin in den Sidecar geladen werden darf.
     *
     * @return leer, wenn das Laden erlaubt ist; sonst ein {@link ProvenanceBlock} mit
     *         Erläuterung und Gegenmaßnahme (fail-closed).
     */
    public Optional<ProvenanceBlock> checkLoad(Plugin plugin) {
        Optional<String> source = readSource(plugin.baseDir());
        if (source.isEmpty()) {
            if (allow.contains(plugin.id())) {
                return Optional.empty();
            }
            return Optional.of(block(plugin.id(),
                    "keine Install-Provenance im Plugin-Ordner ('.jclaw/install.json' fehlt) und die Id ist nicht "
                    + "in plugins.allow gepinnt; Plugin wird nicht geladen. Id '" + plugin.id()
                    + "' pinnten oder aus vertrauenswuerdiger Quelle installieren (OpenClaw: --force)."));
        }
        String value = source.get();
        if (value.isEmpty()) {
            return Optional.of(block(plugin.id(),
                    "ungueltige/konfliktbehaftete Install-Provenance (provenance-invalid): '.jclaw/install.json' ist "
                    + "unlesbar oder ohne gueltiges 'source'-Feld und wird nie automatisch vertraut; Plugin aus "
                    + "vertrauenswuerdiger Quelle neu installieren."));
        }
        if (trustedSources.contains(value) || allow.contains(plugin.id())) {
            return Optional.empty();
        }
        return Optional.of(block(plugin.id(),
                "Install-Quelle '" + value + "' ist nicht freigegeben (trusted-sources: "
                + String.join(", ", trustedSources.stream().sorted().toList()) + "); Quelle vertrauen oder Id '"
                + plugin.id() + "' in plugins.allow pinnten (OpenClaw: --force)."));
    }

    private Optional<String> readSource(String baseDir) {
        Path record = Path.of(baseDir).resolve(PROVENANCE_FILE);
        if (!Files.isRegularFile(record)) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(Files.readString(record, StandardCharsets.UTF_8));
            JsonNode source = node.get(FIELD_SOURCE);
            if (source == null || !source.isTextual() || source.asString().isBlank()) {
                return Optional.of("");
            }
            return Optional.of(source.asString().trim());
        } catch (IOException | RuntimeException e) {
            log.warn("Install-Provenance '{}' nicht lesbar: {}", record, e.getMessage());
            return Optional.of("");
        }
    }

    private static ProvenanceBlock block(String pluginId, String message) {
        return new ProvenanceBlock(pluginId, message);
    }

    /** Beschreibt ein abgewiesenes Plugin-Load (fail-closed): Plugin und Begründung inkl. Gegenmaßnahme. */
    public record ProvenanceBlock(String pluginId, String message) {
    }
}