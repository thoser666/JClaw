package biz.brumm.domain.model;

/**
 * Sichtbare Skills-Workshop-Konfiguration (Control-Plane-Leseansicht der {@code jclaw.agent.skills.workshop.*})-Properties.
 *
 * @param approvalPolicy    Freigabepolitik (PENDING = Agent-Aktionen brauchen Operator-Freigabe).
 * @param maxPending        Max. Anzahl PENDING + QUARANTINED Vorschläge pro Workspace.
 * @param maxSkillBytes     Max. Größe des Proposal-Bodys in Bytes (UTF-8).
 * @param autonomousEnabled Erlaubt agentengetriebenes Auto-Anlegen von Proposals (OpenClaw {@code autonomous.enabled}).
 */
public record SkillWorkshopConfig(
        ApprovalPolicy approvalPolicy,
        int maxPending,
        int maxSkillBytes,
        boolean autonomousEnabled) {
}