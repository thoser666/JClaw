package biz.brumm.infrastructure.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeSidecarBridgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @EnabledIf("nodeAvailable")
    void handshakeProvidesReadyInfo() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            JsonNode info = bridge.readyInfo();
            assertThat(info).isNotNull();
            assertThat(info.path("name").asString()).isEqualTo("jclaw-protocol-sidecar");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void pingReturnsTrue() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThat(bridge.ping()).isTrue();
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void infoReportsNameVersionAndNodeRuntime() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            JsonNode info = bridge.info();
            assertThat(info.path("name").asString()).isEqualTo("jclaw-protocol-sidecar");
            assertThat(info.path("node").asString()).startsWith("v");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void listToolsReportsRegisteredTools() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            List<SidecarToolDescriptor> tools = bridge.listTools();

            assertThat(tools).extracting(SidecarToolDescriptor::name)
                    .containsExactly("add", "echo", "sleep");
            SidecarToolDescriptor add = tools.stream()
                    .filter(t -> t.name().equals("add"))
                    .findFirst().orElseThrow();
            assertThat(add.description()).contains("Summe");
            assertThat(add.parameters()).isNotNull();
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolReturnsToolResult() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("a", 2).put("b", 3);

            JsonNode result = bridge.callTool("add", arguments);

            assertThat(result.path("result").asInt()).isEqualTo(5);
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolUnknownToolRaisesToolNotFound() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThatThrownBy(() -> bridge.callTool("gibtsNicht", null))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_TOOL_NOT_FOUND));
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolWithInvalidArgumentsRaisesToolExecutionError() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("a", "keine Zahl").put("b", 3);

            assertThatThrownBy(() -> bridge.callTool("add", arguments))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_TOOL_EXECUTION))
                    .hasMessageContaining("müssen Zahlen sein");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void unknownMethodRaisesMethodNotFound() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            assertThatThrownBy(() -> bridge.execute("gibtsNicht", null))
                    .isInstanceOf(SidecarCallException.class)
                    .satisfies(e -> assertThat(((SidecarCallException) e).code())
                            .isEqualTo(NodeSidecarBridge.ERROR_METHOD_NOT_FOUND));
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void callToolSlowerThanCallTimeoutRaisesTimeout() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(NodeSidecarBridge.defaultScript(), objectMapper, 300, 5000)) {
            ObjectNode arguments = objectMapper.createObjectNode().put("ms", 2000);

            assertThatThrownBy(() -> bridge.callTool("sleep", arguments))
                    .isInstanceOf(SidecarTimeoutException.class)
                    .hasMessageContaining("300 ms");
        }
    }

    @Test
    @EnabledIf("nodeAvailable")
    void restartStartsFreshProcess() throws IOException {
        try (NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper)) {
            long firstPid = bridge.pid();
            assertThat(bridge.ping()).isTrue();

            bridge.restart();

            assertThat(bridge.pid()).isNotEqualTo(firstPid);
            assertThat(bridge.processAlive()).isTrue();
            assertThat(bridge.ping()).isTrue();
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

        assertThatThrownBy(() -> bridge.callTool("add", objectMapper.createObjectNode().put("a", 1).put("b", 1)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("geschlossen");
    }

    @Test
    @EnabledIf("nodeAvailable")
    void restartAfterCloseFails() throws IOException {
        NodeSidecarBridge bridge = NodeSidecarBridge.start(objectMapper);
        bridge.close();

        assertThatThrownBy(bridge::restart)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Neustart");
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
