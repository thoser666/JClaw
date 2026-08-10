package biz.brumm.domain.model;

/**
 * Eine gespeicherte Konversationsnachricht in Leseform fuer die API.
 *
 * @param role Rollen-Kennung der Nachricht (z. B. SYSTEM, USER, ASSISTANT, TOOL).
 * @param text Textinhalt der Nachricht.
 */
public record ConversationMessage(String role, String text) {
}
