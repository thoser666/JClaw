package biz.brumm.infrastructure.sidecar;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRpcLineCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonRpcLineCodec codec = new JsonRpcLineCodec(objectMapper);

    @Test
    void encodeRequestRoundTrips() {
        ObjectNode params = objectMapper.createObjectNode().put("a", 2).put("b", 3);

        String line = codec.encode(JsonRpcMessage.request(7, "add", params));

        assertThat(line).endsWith("\n");
        JsonRpcMessage decoded = codec.decode(line.strip());
        assertThat(decoded.jsonrpc()).isEqualTo("2.0");
        assertThat(decoded.id()).isEqualTo(7);
        assertThat(decoded.method()).isEqualTo("add");
        assertThat(decoded.isRequest()).isTrue();
        assertThat(decoded.params().get("a").asInt()).isEqualTo(2);
        assertThat(decoded.params().get("b").asInt()).isEqualTo(3);
    }

    @Test
    void encodeResponseRoundTrips() {
        JsonNode result = objectMapper.createObjectNode().put("result", 5);

        JsonRpcMessage decoded = codec.decode(codec.encode(JsonRpcMessage.response(7, result)).strip());

        assertThat(decoded.id()).isEqualTo(7);
        assertThat(decoded.isRequest()).isFalse();
        assertThat(decoded.result().get("result").asInt()).isEqualTo(5);
        assertThat(decoded.error()).isNull();
    }

    @Test
    void encodeErrorRoundTrips() {
        JsonRpcMessage decoded = codec.decode(codec.encode(JsonRpcMessage.error(9, -32002, "kaputt")).strip());

        assertThat(decoded.errorCode()).isEqualTo(-32002);
        assertThat(decoded.errorMessage()).isEqualTo("kaputt");
        assertThat(decoded.result()).isNull();
    }

    @Test
    void encodeNotificationRoundTrips() {
        ObjectNode params = objectMapper.createObjectNode().put("version", "1.0.0");

        JsonRpcMessage decoded = codec.decode(codec.encode(JsonRpcMessage.notification("sidecar.ready", params)).strip());

        assertThat(decoded.id()).isEqualTo(-1);
        assertThat(decoded.isRequest()).isTrue();
        assertThat(decoded.isNotification()).isTrue();
        assertThat(decoded.params().get("version").asString()).isEqualTo("1.0.0");
    }

    @Test
    void encodeOmitsEmptyFields() {
        String line = codec.encode(JsonRpcMessage.response(3, null));

        assertThat(line).contains("jsonrpc").contains("\"id\":3").doesNotContain("method").doesNotContain("result");
    }

    @Test
    void decodeRejectsMalformedLine() {
        assertThatThrownBy(() -> codec.decode("{ kein json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ungültige JSON-RPC-Zeile");
    }

    @Test
    void decodeRejectsNonObjectLine() {
        assertThatThrownBy(() -> codec.decode("[1, 2]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON-Objekt");
    }
}
