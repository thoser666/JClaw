package biz.brumm.config;

import biz.brumm.domain.model.ApprovalPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill-Workshop-Konfiguration (OpenClaw {@code skills.workshop.*}, P4-06).
 * <p>
 * Standardwerte spiegeln OpenClaw: {@code approvalPolicy: "pending"} (Agent-Aktionen
 * brauchen Operator-Freigabe), {@code maxPending: 50}, {@code maxSkillBytes: 40000},
 * {@code autonomous.enabled: false}.
 *
 * @param approvalPolicy Freigabepolitik (default {@link ApprovalPolicy#PENDING}).
 * @param maxPending     Max. PENDING + QUARANTINED Proposals pro Workspace (default 50; ≤ 0 = 50).
 * @param maxSkillBytes  Max. Proposal-Body-Größe in Bytes (default 40000; ≤ 0 = 40000).
 * @param autonomous     Autonomes Proposal-Anlegen aus Konversations-Signalen (default aus).
 */
@ConfigurationProperties(prefix = "jclaw.agent.skills.workshop")
public record SkillWorkshopProperties(
        ApprovalPolicy approvalPolicy,
        int maxPending,
        int maxSkillBytes,
        Autonomous autonomous) {

    public record Autonomous(boolean enabled) {
        public Autonomous {
        }
    }

    public SkillWorkshopProperties {
        approvalPolicy = approvalPolicy == null ? ApprovalPolicy.PENDING : approvalPolicy;
        maxPending = maxPending <= 0 ? 50 : maxPending;
        maxSkillBytes = maxSkillBytes <= 0 ? 40_000 : maxSkillBytes;
        autonomous = autonomous == null ? new Autonomous(false) : autonomous;
    }
}