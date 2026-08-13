package biz.brumm.infrastructure.sidecar;

import java.io.IOException;

/**
 * Wird geworfen, wenn das Node-Sidecar eine Anfrage mit einem strukturierten
 * JSON-RPC-Fehler beantwortet (z. B. unbekanntes Tool, Fehler in der Tool-Ausführung).
 * {@code code} entspricht dem JSON-RPC-Fehlercode des Sidecars (siehe
 * {@code docs/bridge-protocol.md}).
 */
public class SidecarCallException extends IOException {

    private final int code;

    public SidecarCallException(int code, String message, String method) {
        super("Sidecar-Fehler bei '" + method + "' (code " + code + "): " + message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
