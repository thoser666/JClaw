package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Skill;

/**
 * Schreibzugriff auf den Workspace-Skills-Ordner für die Skill-Workshop-Lebend-Schreiboperation
 * ({@code apply}). Die Hash-Bindung ({@code targetHash}) wird über die rohen {@code SKILL.md}-Bytes
 * berechnet, damit eine externe Änderung vor {@code apply} als {@code STALE} erkannt wird.
 */
public interface SkillWorkshopWriter {

    /** Existiert ein lebender Skill (SKILL.md/skill.md) mit diesem Namen im Workspace? */
    boolean exists(String name);

    /** SHA-256 (hex) über den {@code SKILL.md}-Bytes; leer, wenn kein Skill existiert. */
    String contentHash(String name);

    /** Schreibt den Skill als <Skills-Verzeichnis>/&lt;name&gt;/SKILL.md (create/overwrite, der einzige Live-Write). */
    Skill write(Skill skill);
}