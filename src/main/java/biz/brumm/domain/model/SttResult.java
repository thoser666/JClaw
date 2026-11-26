package biz.brumm.domain.model;

/**
 * Ergebnis einer STT-Transkription (P4-03).
 *
 * @param text transkribierter Text (nie {@code null})
 * @param language erkannte Sprache (ISO-639-1, optional)
 * @param confidence Konfidenzwert 0.0–1.0 (optional)
 */
public record SttResult(String text, String language, Double confidence) {
}