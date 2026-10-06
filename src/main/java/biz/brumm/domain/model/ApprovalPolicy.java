package biz.brumm.domain.model;

/**
 * Skills-Workshop-Freigabepolitik (OpenClaw {@code skills.workshop.approvalPolicy}).
 *
 * @see biz.brumm.config.SkillWorkshopProperties
 */
public enum ApprovalPolicy {

    /** Agent-initiierte Lifecycle-Aktionen (apply/reject/quarantine) brauchen Operator-Freigabe. */
    PENDING,

    /** Agent-initiierte Lifecycle-Aktionen laufen ohne zusätzliche Freigabe. */
    AUTO
}