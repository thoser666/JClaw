package biz.brumm.domain.model;

/**
 * Status eines Session-Ziels (OpenClaw-kompatible Goal-Stati).
 */
public enum GoalStatus {

    ACTIVE,

    PAUSED,

    BLOCKED,

    BUDGET_LIMITED,

    USAGE_LIMITED,

    COMPLETE
}