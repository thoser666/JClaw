package biz.brumm.config;

import biz.brumm.domain.port.out.PluginHookDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stellt den {@link PluginHookDispatcher} immer bereit: Mit aktivierter Plugin-Runtime
 * ({@code jclaw.agent.plugins.runtime.enabled=true}) ist {@code NodeSidecarPluginRuntime}
 * die Implementierung; sonst greift hier die No-op-Bean (Deny-by-Default, analog zur
 * Runtime selbst). Genau eine Bean existiert also unabhängig von der Konfiguration —
 * Services können den Dispatcher bedenkenlos injizieren.
 */
@Configuration
public class PluginHookConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "jclaw.agent.plugins.runtime", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    public PluginHookDispatcher pluginHookDispatcher() {
        return PluginHookDispatcher.noop();
    }
}