package biz.brumm.domain.model;

/**
 * Ergebnis einer TTS-Synthese (P4-03).
 *
 * @param audio      Rohdaten der Audiodatei (nie {@code null})
 * @param mimeType   MIME-Typ der Audiodatei (z. B. {@code audio/mpeg})
 * @param voice      verwendete Stimme
 * @param durationMs geschätzte Dauer in Millisekunden (optional, {@code null} wenn unbekannt)
 */
public record TtsResult(byte[] audio, String mimeType, String voice, Long durationMs) {
}