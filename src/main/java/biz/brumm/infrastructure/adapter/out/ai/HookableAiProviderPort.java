package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.model.HookResult;
import biz.brumm.domain.port.out.AiProviderPort;
import biz.brumm.domain.port.out.HookCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Decorator für AiProviderPort, der Lifecycle-Hooks vor/nach dem Agentenlauf ausführt.
 * Wird nur aktiv geschaltet, wenn Hooks aktiviert sind.
 */
public class HookableAiProviderPort implements AiProviderPort {

    private static final Logger log = LoggerFactory.getLogger(HookableAiProviderPort.class);

    private final AiProviderPort delegate;
    private final HookCallback hookCallback;

    public HookableAiProviderPort(AiProviderPort delegate, HookCallback hookCallback) {
        this.delegate = delegate;
        this.hookCallback = hookCallback;
    }

    @Override
    public AgentResponse execute(AgentCommand command, String systemPrompt, int maxIterations) {
        // before_agent_run Hook
        HookContext beforeContext = HookContext.forAgentRun("before_agent_run", command.prompt(), command.contextId());
        List<HookResult> beforeResults = hookCallback.executeStage("before_agent_run", beforeContext);
        for (HookResult result : beforeResults) {
            if (!result.allowed()) {
                log.info("Agent-Lauf blockiert durch Hook '{}': {}", result.hookName(), result.output());
                return new AgentResponse("Agent-Lauf blockiert durch Hook: " + result.output(),
                        java.time.Instant.now(), List.of(), 0, null);
            }
        }

        // Delegate ausführen
        AgentResponse response = delegate.execute(command, systemPrompt, maxIterations);

        // after_agent_run Hook
        HookContext afterContext = HookContext.forStage("after_agent_run");
        hookCallback.executeStage("after_agent_run", afterContext);

        return response;
    }
}
