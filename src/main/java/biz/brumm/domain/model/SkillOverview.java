package biz.brumm.domain.model;

/**
 * Lese-Ansicht eines Skills für die API.
 *
 * @param name        Eindeutiger Skill-Name.
 * @param description Kurzbeschreibung, wofuer der Skill gedacht ist.
 * @param enabled     Ob der Skill per {@code jclaw.agent.skills.enabled} aktiviert ist.
 */
public record SkillOverview(String name, String description, boolean enabled) {
}
