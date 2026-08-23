package biz.brumm.config;

import biz.brumm.domain.model.HookContext;
import biz.brumm.domain.port.out.HookCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Feuert Gateway-Lifecycle-Hooks (gateway_start, gateway_stop) bei Anwendungsstart/Stop.
 */
@Component
@ConditionalOnProperty(prefix = "jclaw.hooks", name = "enabled", havingValue = "true")
public class HookLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(HookLifecycleListener.class);

    private final HookCallback hookCallback;

    public HookLifecycleListener(HookCallback hookCallback) {
        this.hookCallback = hookCallback;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        log.info("Gateway gestartet — führe gateway_start Hooks aus.");
        hookCallback.executeStage("gateway_start", HookContext.forStage("gateway_start"));
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent event) {
        log.info("Gateway wird beendet — führe gateway_stop Hooks aus.");
        hookCallback.executeStage("gateway_stop", HookContext.forStage("gateway_stop"));
    }
}
