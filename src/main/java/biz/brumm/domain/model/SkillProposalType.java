package biz.brumm.domain.model;

/**
 * Art eines Skill-Workshop-Vorschlags.
 */
public enum SkillProposalType {

    /** Neuen Skill anlegen ({@code propose-create}); scheitert, wenn der Skill bereits existiert (No-Clobber). */
    CREATE,

    /** Bestehenden Workspace-Skill aktualisieren ({@code propose-update}); an aktuellen Ziel-Hash gebunden (stale bei Veränderung). */
    UPDATE
}