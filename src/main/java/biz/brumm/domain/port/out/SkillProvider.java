package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Skill;

import java.util.List;

/**
 * Liefert die im konfigurierten Verzeichnis gefundenen Skills (AgentSkills-Format).
 */
public interface SkillProvider {

    List<Skill> findAll();
}
