package biz.brumm.domain.model;

/**
 * Plugin-Bundle-Formate, die JClaw erkennt (OpenClaw + kompatible fremde Bundles).
 *
 * @param manifestLocation Relativer Pfad der Manifest-Datei innerhalb des Plugin-Ordners.
 */
public enum PluginType {

    OPENCLAW("openclaw.plugin.json"),
    AGENT_PLUGINS("plugin.json"),
    CODEX(".codex-plugin/plugin.json"),
    CLAUDE(".claude-plugin/plugin.json"),
    CURSOR(".cursor-plugin/plugin.json");

    private final String manifestLocation;

    PluginType(String manifestLocation) {
        this.manifestLocation = manifestLocation;
    }

    public String manifestLocation() {
        return manifestLocation;
    }
}
