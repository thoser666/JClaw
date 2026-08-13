package biz.brumm.infrastructure.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeSidecarBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @EnabledIf("nodeAvailable")
    void callAddMethodReturnsSum() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            ObjectNode params = objectMapper.createObjectNode().put("a", 2).put("b", 3);

            JsonRpcMessage response = bridge.call("add", params);

            assertThat(response.id()).isEqualTo(1);
            assertThat(response.result().asInt()).isEqualTo(5);
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void unknownMethodReportsSidecarError() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThatThrownBy(() -> bridge.call("gibtsNicht", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unbekannte Methode");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void closeTerminatesSidecarProcess() throws IOException {
        NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper);
        assertThat(bridge.processAlive()).isTrue();

        bridge.close();

        assertThat(bridge.processAlive()).isFalse();
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callAfterCloseFails() throws IOException {
        NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper);
        bridge.close();

        assertThatThrownBy(() -> bridge.call("add", objectMapper.createObjectNode().put("a", 1).put("b", 1)))
                .isInstanceOf(IOException.class);
    }

    private static boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
