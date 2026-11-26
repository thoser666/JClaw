package biz.brumm.domain.port.out;

import biz.brumm.domain.model.MediaAttachment;

/**
 * Port für Sprachmedien (P4-03 Speech/Media-Provider).
 * <p>
 * Bietet Text-zu-Sprache (TTS) und Sprache-zu-Text (STT) über einen
 * konfigurierbaren Provider (z. B. OpenAI-kompatibel). Das Feature ist
 * Deny-by-Default gated ({@code jclaw.media.provider.enabled}).
 */
public interface MediaProvider {

    /**
     * Gibt den Namen des zugrundeliegenden Providers zurück
     * (z. B. {@code openai}, {@code ollama}).
     */
    String providerName();

    /**
     * Wandelt Text in gesprochenes Audio um.
     *
     * @param text     der zu vertonende Text (nicht leer)
     * @param voice    gewünschte Stimme (Provider-abhängig, kann {@code null} sein)
     * @param format   Audio-Format (z. B. {@code mp3}, {@code opus}, {@code wav})
     * @return Rohdaten der Audiodatei (nie {@code null})
     * @throws MediaProvider.MediaProviderException bei Fehlern
     */
    byte[] textToSpeech(String text, String voice, String format) throws MediaProvider.MediaProviderException;

    /**
     * Wandelt Audiodaten in Text um.
     *
     * @param audio    Rohdaten der Audiodatei (nicht leer)
     * @param format   Audio-Format der Eingabedaten
     * @return transkribierter Text (nie {@code null}, kann leer sein)
     * @throws MediaProvider.MediaProviderException bei Fehlern
     */
    String speechToText(byte[] audio, String format) throws MediaProvider.MediaProviderException;

    /**
     * @return {@code true}, wenn der Provider verfügbar und konfiguriert ist
     */
    boolean isAvailable();

    /**
     * Ausnahme bei Medien-Provider-Fehlern.
     */
    class MediaProviderException extends Exception {
        public MediaProviderException(String message) {
            super(message);
        }

        public MediaProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}