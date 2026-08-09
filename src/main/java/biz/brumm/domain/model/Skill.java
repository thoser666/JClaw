package biz.brumm.domain.model;

/**
 * Ein Skill nach der AgentSkills-Spec (OpenClaw-kompatibel).
 *
 * @param name        Eindeutiger Skill-Name (aus dem Frontmatter).
 * @param description Kurzbeschreibung, wofuer der Skill gedacht ist.
 * @param content     Markdown-Body des SKILL.md (Anweisungen fuer das Modell).
 * @param baseDir     Absoluter Pfad des Skill-Verzeichnisses (fuer {baseDir}-Referenzen).
 */
public record Skill(String name, String description, String content, String baseDir) {
}
