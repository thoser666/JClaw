package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.HookCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HookableAiProviderPortTest {

    @Mock
    private AiProviderPort delegate;

    @Mock
    private HookCallback hookCallback;

    @Test
    void executeRunsDelegateWhenHooksProceed() {
        AgentResponse expected = AgentResponse.of("ok");
        when(hookCallback.executeStage(eq("before_agent_run"), any(HookContext.class)))
                .thenReturn(List.of(HookResult.proceed("h1")));
        when(delegate.execute(any(), any(), eq(8))).thenReturn(expected);
        when(hookCallback.executeStage(eq("after_agent_run"), any(HookContext.class)))
                .thenReturn(List.of());

        HookableAiProviderPort port = new HookableAiProviderPort(delegate, hookCallback);
        AgentResponse response = port.execute(new AgentCommand("test", "ctx"), "system", 8);

        assertThat(response.content()).isEqualTo("ok");
        verify(delegate).execute(any(), any(), eq(8));
    }

    @Test
    void executeReturnsBlockedWhenHookBlocks() {
        when(hookCallback.executeStage(eq("before_agent_run"), any(HookContext.class)))
                .thenReturn(List.of(HookResult.block("h1", "blocked by hook")));

        HookableAiProviderPort port = new HookableAiProviderPort(delegate, hookCallback);
        AgentResponse response = port.execute(new AgentCommand("test", "ctx"), "system", 8);

        assertThat(response.content()).contains("blockiert");
        verify(delegate, never()).execute(any(), any(), eq(8));
    }

    @Test
    void executeCallsAfterHookOnSuccess() {
        AgentResponse expected = AgentResponse.of("ok");
        when(hookCallback.executeStage(eq("before_agent_run"), any(HookContext.class)))
                .thenReturn(List.of());
        when(delegate.execute(any(), any(), eq(8))).thenReturn(expected);
        when(hookCallback.executeStage(eq("after_agent_run"), any(HookContext.class)))
                .thenReturn(List.of());

        HookableAiProviderPort port = new HookableAiProviderPort(delegate, hookCallback);
        port.execute(new AgentCommand("test", "ctx"), "system", 8);

        verify(hookCallback).executeStage(eq("after_agent_run"), any(HookContext.class));
    }
}
