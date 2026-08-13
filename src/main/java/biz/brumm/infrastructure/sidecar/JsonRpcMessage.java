package biz.brumm.infrastructure.sidecar;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Eine JSON-RPC-2.0-Nachricht für die Kommunikation mit einem Node-Sidecar
 * (siehe {@code docs/bridge-protocol.md}).
 * <p>
 * Entweder eine Anfrage ({@code method} != {@code null}), eine Antwort
 * ({@code result}) oder ein Fehler ({@code error}, strukturiert als Objekt
 * {@code {code, message}}). Notifications sind Anfragen ohne gültige id
 * ({@code id < 0}) und werden vom Empfänger nicht beantwortet.
 */
public record JsonRpcMessage(String jsonrpc, long id, String method, JsonNode params, JsonNode result, JsonNode error) {

    public static JsonRpcMessage request(long id, String method, JsonNode params) {
        return new JsonRpcMessage("2.0", id, method, params, null, null);
    }

    public static JsonRpcMessage notification(String method, JsonNode params) {
        return new JsonRpcMessage("2.0", -1, method, params, null, null);
    }

    public static JsonRpcMessage response(long id, JsonNode result) {
        return new JsonRpcMessage("2.0", id, null, null, result, null);
    }

    public static JsonRpcMessage error(long id, int code, String message) {
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message);
        return new JsonRpcMessage("2.0", id, null, null, null, error);
    }

    public boolean isRequest() {
        return method != null;
    }

    public boolean isNotification() {
        return method != null && id < 0;
    }

    public int errorCode() {
        return error != null ? error.path("code").asInt() : 0;
    }

    public String errorMessage() {
        return error != null ? error.path("message").asString() : null;
    }
}
