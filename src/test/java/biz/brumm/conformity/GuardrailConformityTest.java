package biz.brumm.conformity;

import biz.brumm.config.AuthProperties;
import biz.brumm.config.GuardrailProperties;
import biz.brumm.config.PluginRuntimeProperties;
import biz.brumm.config.ToolPolicyProperties;
import biz.brumm.domain.service.CredentialLeakGuard;
import biz.brumm.domain.service.SecretEgressGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-05: Guardrail-Konformitätstest (Paritäts-Testsuite, Deny-by-Default-Gate).
 * <p>
 * Sichert die sicherheitskritischen Defaults der Anwendung: Guardrails sind Beans und
 * schützen Outbound-Daten nur, wenn explizit aktiviert; Feature-Schalter und Policy-Listen
 * sind im Auslieferungszustand aus (deny-by-default). Eine Änderung dieser Defaults bricht
 * den Test bewusst, damit sie als bewusste Entscheidung dokumentiert wird.
 */
@SpringBootTest
class GuardrailConformityTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private GuardrailProperties guardrail;
    @Autowired
    private AuthProperties auth;
    @Autowired
    private PluginRuntimeProperties pluginRuntime;
    @Autowired
    private ToolPolicyProperties toolPolicy;

    @Test
    void guardBeansAreWired() {
        assertThat(context.getBean(CredentialLeakGuard.class))
                .describedAs("CredentialLeakGuard muss als Bean verdrahtet sein.")
                .isNotNull();
        assertThat(context.getBean(SecretEgressGuard.class))
                .describedAs("SecretEgressGuard muss als Bean verdrahtet sein.")
                .isNotNull();
    }

    @Test
    void securityDefaultsAreDenyByDefault() {
        assertThat(guardrail.enabled())
                .describedAs("Guardrail-Redaktion darf nicht ohne explizite Freigabe aktiv sein.")
                .isFalse();
        assertThat(guardrail.bindings())
                .describedAs("Secret-Egress-Host-Bindings müssen leer (fail-closed) sein.")
                .isEmpty();
        assertThat(auth.enabled())
                .describedAs("Auth darf nicht ohne explizite Freigabe aktiv sein.")
                .isFalse();
        assertThat(auth.publicPaths())
                .describedAs("Öffentliche Pfade dürfen nicht konfiguriert sein (deny-by-default).")
                .isEmpty();
    }

    @Test
    void pluginAndToolDefaultsAreDenyByDefault() {
        assertThat(pluginRuntime.enabled())
                .describedAs("Plugin-Laufzeit (Node-Sidecar) darf nicht ohne Freigabe starten.")
                .isFalse();
        assertThat(pluginRuntime.allow())
                .describedAs("Keine Plugin-Operation-Freigaben ohne explizite Konfiguration.")
                .isEmpty();
        assertThat(toolPolicy.allow())
                .describedAs("Tool-Allowliste muss ohne Voreinträge ausgeliefert werden.")
                .isEmpty();
        assertThat(toolPolicy.deny())
                .describedAs("Tool-Denyliste muss ohne Voreinträge ausgeliefert werden.")
                .isEmpty();
    }
}