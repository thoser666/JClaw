package biz.brumm.infrastructure.sidecar;

import java.io.IOException;

/**
 * Wird geworfen, wenn das Node-Sidecar nicht innerhalb des konfigurierten
 * Call- bzw. Ready-Timeout antwortet (siehe {@code docs/bridge-protocol.md}).
 */
public class SidecarTimeoutException extends IOException {

    public SidecarTimeoutException(String operation, long timeoutMillis) {
        super("Sidecar hat auf '" + operation + "' nicht innerhalb von " + timeoutMillis + " ms geantwortet.");
    }
}
