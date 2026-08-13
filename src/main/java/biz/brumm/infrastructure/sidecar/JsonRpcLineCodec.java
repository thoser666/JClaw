package biz.brumm.infrastructure.sidecar;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Codiert/decodiert JSON-RPC-2.0-Nachrichten als Newline-delimited JSON (eine Nachricht
 * pro Zeile). Dieses Framing ist die Basis des Bridge-Protokolls zwischen dem Java-Kern
 * und dem Node-Sidecar (siehe ADR-0001 und {@code docs/bridge-protocol.md}).
 */
public final class JsonRpcLineCodec {

    private final ObjectMapper objectMapper;

    public JsonRpcLineCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(JsonRpcMessage message) throws JacksonException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("jsonrpc", message.jsonrpc());
        node.put("id", message.id());
        if (message.method() != null) {
            node.put("method", message.method());
        }
        if (message.params() != null) {
            node.set("params", message.params());
        }
        if (message.result() != null) {
            node.set("result", message.result());
        }
        if (message.error() != null) {
            node.set("error", message.error());
        }
        return objectMapper.writeValueAsString(node) + "\n";
    }

    public JsonRpcMessage decode(String line) {
        JsonNode root;
        try {
            root = objectMapper.readTree(line);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Ungültige JSON-RPC-Zeile: " + e.getOriginalMessage());
        }
        if (!(root instanceof ObjectNode object)) {
            throw new IllegalArgumentException("JSON-RPC-Nachricht muss ein JSON-Objekt sein.");
        }
        long id = object.hasNonNull("id") ? object.get("id").asLong() : -1;
        String jsonrpc = object.hasNonNull("jsonrpc") ? object.get("jsonrpc").asString() : null;
        String method = object.hasNonNull("method") ? object.get("method").asString() : null;
        JsonNode params = object.get("params");
        JsonNode result = object.get("result");
        JsonNode error = object.get("error");
        return new JsonRpcMessage(jsonrpc, id, method, params, result, error);
    }
}
