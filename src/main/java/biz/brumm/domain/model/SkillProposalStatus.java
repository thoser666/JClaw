package biz.brumm.domain.model;

/**
 * Lebenszyklus eines Skill-Workshop-Vorschlags (OpenClaw-Workshop-Lifecycle).
 * Nur {@code PENDING}-Vorschläge dürfen revidiert, angewendet, abgelehnt oder quarantäniert werden.
 */
public enum SkillProposalStatus {

    /** Entwurf in Prüfung (aus create/update/revise). */
    PENDING,

    /** Angewendet — der Skill wurde live in den Workspace geschrieben. */
    APPLIED,

    /** Abgelehnt durch den Operator. */
    REJECTED,

    /** Zur manuellen Prüfung zurückgestellt (zählt gegen maxPending). */
    QUARANTINED,

    /** Ziel hat sich geändert (No-Clobber verletzt oder Hash-Bindung gebrochen). */
    STALE
}