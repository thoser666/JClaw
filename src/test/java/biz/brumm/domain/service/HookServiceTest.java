package biz.brumm.domain.service;

import biz.brumm.domain.model.Hook;
import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;
import biz.brumm.domain.port.out.HookProvider;
import biz.brumm.infrastructure.adapter.out.hook.HookScriptExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HookServiceTest {

    @Mock
    private HookProvider hookProvider;

    @Mock
    private HookScriptExecutor executor;

    @Test
    void executeHooksReturnsEmptyWhenNoHooks() {
        when(hookProvider.findByStage("before_agent_run")).thenReturn(List.of());
        HookService service = new HookService(hookProvider, executor);

        List<HookResult> results = service.executeHooks("before_agent_run",
                HookContext.forStage("before_agent_run"));

        assertThat(results).isEmpty();
    }

    @Test
    void executeHooksRunsAllHooksWhenAllProceed() {
        Hook hook1 = new Hook("h1", "before_agent_run", 10, Path.of("/h1"), "");
        Hook hook2 = new Hook("h2", "before_agent_run", 5, Path.of("/h2"), "");
        when(hookProvider.findByStage("before_agent_run")).thenReturn(List.of(hook1, hook2));
        when(executor.execute(any(Hook.class), any(HookContext.class))).thenReturn(HookResult.proceed("h1"));

        HookService service = new HookService(hookProvider, executor);
        List<HookResult> results = service.executeHooks("before_agent_run",
                HookContext.forStage("before_agent_run"));

        assertThat(results).hasSize(2);
        verify(executor).execute(eq(hook1), any(HookContext.class));
        verify(executor).execute(eq(hook2), any(HookContext.class));
    }

    @Test
    void executeHooksStopsOnBlock() {
        Hook hook1 = new Hook("h1", "before_agent_run", 10, Path.of("/h1"), "");
        Hook hook2 = new Hook("h2", "before_agent_run", 5, Path.of("/h2"), "");
        when(hookProvider.findByStage("before_agent_run")).thenReturn(List.of(hook1, hook2));
        when(executor.execute(eq(hook1), any(HookContext.class))).thenReturn(HookResult.block("h1", "blocked"));

        HookService service = new HookService(hookProvider, executor);
        List<HookResult> results = service.executeHooks("before_agent_run",
                HookContext.forStage("before_agent_run"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).allowed()).isFalse();
        verify(executor, never()).execute(eq(hook2), any(HookContext.class));
    }

    @Test
    void executeAndProceedReturnsTrueWhenAllProceed() {
        Hook hook = new Hook("h1", "before_agent_run", 10, Path.of("/h1"), "");
        when(hookProvider.findByStage("before_agent_run")).thenReturn(List.of(hook));
        when(executor.execute(any(Hook.class), any(HookContext.class))).thenReturn(HookResult.proceed("h1"));

        HookService service = new HookService(hookProvider, executor);
        boolean proceed = service.executeAndProceed("before_agent_run",
                HookContext.forStage("before_agent_run"));

        assertThat(proceed).isTrue();
    }

    @Test
    void executeAndProceedReturnsFalseOnBlock() {
        Hook hook = new Hook("h1", "before_agent_run", 10, Path.of("/h1"), "");
        when(hookProvider.findByStage("before_agent_run")).thenReturn(List.of(hook));
        when(executor.execute(any(Hook.class), any(HookContext.class))).thenReturn(HookResult.block("h1", "blocked"));

        HookService service = new HookService(hookProvider, executor);
        boolean proceed = service.executeAndProceed("before_agent_run",
                HookContext.forStage("before_agent_run"));

        assertThat(proceed).isFalse();
    }

    @Test
    void beforeToolCallReturnsProceedWhenNoHooks() {
        when(hookProvider.findByStage("before_tool_call")).thenReturn(List.of());
        HookService service = new HookService(hookProvider, executor);

        HookResult result = service.beforeToolCall("readFile", "{}");

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void beforeToolCallReturnsBlockWhenHookBlocks() {
        Hook hook = new Hook("h1", "before_tool_call", 10, Path.of("/h1"), "");
        when(hookProvider.findByStage("before_tool_call")).thenReturn(List.of(hook));
        when(executor.execute(any(Hook.class), any(HookContext.class))).thenReturn(HookResult.block("h1", "not allowed"));

        HookService service = new HookService(hookProvider, executor);
        HookResult result = service.beforeToolCall("readFile", "{}");

        assertThat(result.allowed()).isFalse();
    }

    @Test
    void listAllDelegatesToProvider() {
        Hook hook = new Hook("h1", "before_agent_run", 10, Path.of("/h1"), "");
        when(hookProvider.findAll()).thenReturn(List.of(hook));

        HookService service = new HookService(hookProvider, executor);
        List<Hook> hooks = service.listAll();

        assertThat(hooks).containsExactly(hook);
    }
}
