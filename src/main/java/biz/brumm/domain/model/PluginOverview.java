package biz.brumm.domain.model;

/**
 * REST-Sicht auf ein Plugin (ohne interne Pfadangaben).
 */
public record PluginOverview(String id, String name, String version, String description,
                             PluginType type, boolean valid, String validationMessage) {
}
