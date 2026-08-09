package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPropertiesTest {

    @Test
    void appliesDefaultsForMissingValues() {
        SkillProperties properties = new SkillProperties(null, null);

        assertThat(properties.dir()).isEqualTo("./skills");
        assertThat(properties.enabled()).isEmpty();
    }

    @Test
    void keepsConfiguredValues() {
        SkillProperties properties = new SkillProperties("/tmp/skills", List.of("code-review", "docs"));

        assertThat(properties.dir()).isEqualTo("/tmp/skills");
        assertThat(properties.enabled()).containsExactly("code-review", "docs");
    }

    @Test
    void blanksConfiguredDirAreReplacedWithDefault() {
        SkillProperties properties = new SkillProperties("   ", List.of("code-review"));

        assertThat(properties.dir()).isEqualTo("./skills");
    }
}
