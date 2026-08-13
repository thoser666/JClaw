package biz.brumm.infrastructure.sidecar;

import tools.jackson.databind.JsonNode;

/**
 * Eine JSON-RPC-2.0-Nachricht für die Kommunikation mit einem Node-Sidecar.
 * Entweder eine Anfrage ({@code method} != {@code null}) oder eine Antwort
 * ({@code result} bzw. {@code error}).
 */
public record JsonRpcMessage(String jsonrpc, long id, String method, JsonNode params, JsonNode result, String error) {

    public static JsonRpcMessage request(long id, String method, JsonNode params) {
        return new JsonRpcMessage("2.0", id, method, params, null, null);
    }

    public static JsonRpcMessage response(long id, JsonNode result) {
        return new JsonRpcMessage("2.0", id, null, null, result, null);
    }

    public static JsonRpcMessage error(long id, String error) {
        return new JsonRpcMessage("2.0", id, null, null, null, error);
    }

    public boolean isRequest() {
        return method != null;
    }
}
