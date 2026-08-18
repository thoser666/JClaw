package biz.brumm.config.json5;

import java.util.List;

/**
 * Exception, die bei Validierungsfehlern in der JSON5-Konfiguration geworfen wird.
 * Verhindert das Starten der Anwendung.
 */
public class Json5ConfigValidationException extends RuntimeException {

    private final List<String> errors;

    public Json5ConfigValidationException(List<String> errors) {
        super("JSON5-Konfiguration ungültig:\n  - " + String.join("\n  - ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
