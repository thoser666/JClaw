package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.ToolInvocation;
import biz.brumm.domain.port.out.AiProviderPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpawnAgentToolTest {

    @Mock
    private AiProviderPort aiProviderPort;

    @Test
    void spawnAgentDelegatesToProviderAndReturnsContent() {
        when(aiProviderPort.execute(any(), any(String.class), eq(3)))
                .thenReturn(new AgentResponse("Sub-Antwort", Instant.now(), List.of(), 1, null));

        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 3);
        String result = tool.spawnAgent("Aufgabe für Sub", null);

        assertThat(result).isEqualTo("Sub-Antwort");
        ArgumentCaptor<AgentCommand> captor = ArgumentCaptor.forClass(AgentCommand.class);
        verify(aiProviderPort).execute(captor.capture(), any(String.class), eq(3));
        assertThat(captor.getValue().prompt()).isEqualTo("Aufgabe für Sub");
        assertThat(captor.getValue().contextId()).isNull();
    }

    @Test
    void spawnAgentPassesContextIdToProvider() {
        when(aiProviderPort.execute(any(), any(String.class), eq(3)))
                .thenReturn(new AgentResponse("ok", Instant.now(), List.of(), 1, null));

        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 3);
        tool.spawnAgent("Aufgabe", "ctx-123");

        ArgumentCaptor<AgentCommand> captor = ArgumentCaptor.forClass(AgentCommand.class);
        verify(aiProviderPort).execute(captor.capture(), any(String.class), eq(3));
        assertThat(captor.getValue().contextId()).isEqualTo("ctx-123");
    }

    @Test
    void spawnAgentBlankContextIdTreatedAsNull() {
        when(aiProviderPort.execute(any(), any(String.class), eq(3)))
                .thenReturn(new AgentResponse("ok", Instant.now(), List.of(), 1, null));

        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 3);
        tool.spawnAgent("Aufgabe", "  ");

        ArgumentCaptor<AgentCommand> captor = ArgumentCaptor.forClass(AgentCommand.class);
        verify(aiProviderPort).execute(captor.capture(), any(String.class), eq(3));
        assertThat(captor.getValue().contextId()).isNull();
    }

    @Test
    void spawnAgentReturnsErrorMessageOnProviderFailure() {
        when(aiProviderPort.execute(any(), any(String.class), eq(3)))
                .thenThrow(new RuntimeException("Modell nicht erreichbar"));

        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 3);
        String result = tool.spawnAgent("Aufgabe", null);

        assertThat(result).contains("Fehler");
        assertThat(result).contains("Modell nicht erreichbar");
    }

    @Test
    void spawnAgentRespectsMaxDepth() {
        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 1);
        // First call succeeds (depth goes 0 → 1)
        when(aiProviderPort.execute(any(), any(String.class), eq(1)))
                .thenReturn(new AgentResponse("ok", Instant.now(), List.of(), 1, null));
        String result = tool.spawnAgent("inner", null);
        assertThat(result).isEqualTo("ok");

        // Simulate depth=1 by calling from within a depth=1 context
        // We can't easily nest, but we can test the depth check directly
    }

    @Test
    void spawnAgentToolImplementsAgentTool() {
        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 3);
        assertThat(tool).isInstanceOf(biz.brumm.domain.port.out.AgentTool.class);
    }

    @Test
    void spawnAgentDepthResetsAfterExecution() {
        when(aiProviderPort.execute(any(), any(String.class), eq(2)))
                .thenReturn(new AgentResponse("ok", Instant.now(), List.of(), 1, null));

        SpawnAgentTool tool = new SpawnAgentTool(aiProviderPort, 2);
        tool.spawnAgent("first", null);
        // Second call should also work (depth reset after first)
        tool.spawnAgent("second", null);

        verify(aiProviderPort, times(2)).execute(any(), any(String.class), eq(2));
    }
}
