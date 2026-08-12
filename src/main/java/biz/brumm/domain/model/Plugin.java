package biz.brumm.domain.model;

/**
 * Ein Plugin, gelesen aus einem Manifest im Control-Plane-Format (ohne Codeausfuehrung).
 *
 * @param id                 Eindeutiger Bezeichner ({@code id} bei OpenClaw, sonst {@code name}).
 * @param name               Anzeigename aus dem Manifest.
 * @param version            Versionsangabe.
 * @param description        Kurzbeschreibung.
 * @param type               Erkanntes Bundle-Format.
 * @param baseDir            Absoluter Pfad des Plugin-Ordners.
 * @param valid              Ergebnis der Control-Plane-Validierung (Pflichtfelder, Schema-Struktur).
 * @param validationMessage  Fehlermeldungen der Validierung (leer, wenn {@code valid}).
 */
public record Plugin(String id, String name, String version, String description,
                     PluginType type, String baseDir, boolean valid, String validationMessage) {
}
