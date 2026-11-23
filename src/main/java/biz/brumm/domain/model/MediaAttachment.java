package biz.brumm.domain.model;

/**
 * Medien-Anhang für Channel-Nachrichten (P3-07 Media-Message-Support).
 * <p>
 * Trägt die Rohdaten eines Anhangs (Audio, Bild, Dokument) samt MIME-Typ und
 * Dateiname. Die {@link MediaKind} bestimmt, welchen Endpunkt ein Adapter
 * verwendet (z. B. {@code sendVoice}/{@code sendAudio} bei Telegram).
 *
 * @param kind     Art des Anhangs (Default {@link MediaKind#DOCUMENT})
 * @param mimeType MIME-Typ (Default je nach Kind)
 * @param fileName Dateiname ohne Pfad (optional)
 * @param data     Rohdaten (nie {@code null}, leer wenn nicht gesetzt)
 */
public record MediaAttachment(MediaKind kind, String mimeType, String fileName, byte[] data) {

    public MediaAttachment {
        if (kind == null) {
            kind = MediaKind.DOCUMENT;
        }
        if (data == null) {
            data = new byte[0];
        }
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = kind.defaultMimeType();
        }
        if (fileName != null && fileName.isBlank()) {
            fileName = null;
        }
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    /**
     * @return {@code true}, wenn die Audio-Daten als Sprachnachricht (Ogg/Opus)
     *         gelten und nicht als Datei-Anhang
     */
    public boolean isVoiceNote() {
        String mime = mimeType.toLowerCase();
        return kind == MediaKind.AUDIO && (mime.contains("ogg") || mime.contains("opus"));
    }

    public enum MediaKind {
        AUDIO, IMAGE, DOCUMENT;

        public String defaultMimeType() {
            return switch (this) {
                case AUDIO -> "audio/ogg";
                case IMAGE -> "image/png";
                case DOCUMENT -> "application/octet-stream";
            };
        }
    }
}