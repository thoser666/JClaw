package biz.brumm.domain.model;

/**
 * Ergebnis eines Hook-Aufrufs.
 *
 * @param allowed Ob die Ausführung fortgesetzt werden soll (true = proceed, false = block)
 * @param output  Optionale Ausgabe des Scripts (kann Kontext modifizieren)
 * @param hookName Name des Hooks, der das Ergebnis erzeugt hat
 */
public record HookResult(boolean allowed, String output, String hookName) {

    public static HookResult proceed(String hookName) {
        return new HookResult(true, null, hookName);
    }

    public static HookResult proceed(String hookName, String output) {
        return new HookResult(true, output, hookName);
    }

    public static HookResult block(String hookName, String reason) {
        return new HookResult(false, reason, hookName);
    }
}
