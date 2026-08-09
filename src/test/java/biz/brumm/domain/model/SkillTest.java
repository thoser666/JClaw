package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTest {

    @Test
    void exposesAllFields() {
        Skill skill = new Skill("code-review", "Prueft PRs.", "Inhalt.", "/skills/code-review");

        assertThat(skill.name()).isEqualTo("code-review");
        assertThat(skill.description()).isEqualTo("Prueft PRs.");
        assertThat(skill.content()).isEqualTo("Inhalt.");
        assertThat(skill.baseDir()).isEqualTo("/skills/code-review");
    }
}
