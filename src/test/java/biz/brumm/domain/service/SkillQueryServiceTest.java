package biz.brumm.domain.service;

import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.model.SkillOverview;
import biz.brumm.domain.port.out.SkillProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillQueryServiceTest {

    @Mock
    private SkillProvider skillProvider;

    @Test
    void listSkillsMarksEnabledSkills() {
        SkillProperties skillProperties = new SkillProperties("./skills", List.of("code-review"));
        SkillQueryService service = new SkillQueryService(skillProvider, skillProperties);
        when(skillProvider.findAll()).thenReturn(List.of(
                new Skill("code-review", "Prueft PRs.", "Inhalt.", "/skills/code-review"),
                new Skill("docs", "Schreibt Doku.", "Inhalt.", "/skills/docs")));

        List<SkillOverview> overviews = service.listSkills();

        assertThat(overviews).extracting(SkillOverview::name).containsExactly("code-review", "docs");
        assertThat(overviews.get(0).enabled()).isTrue();
        assertThat(overviews.get(1).enabled()).isFalse();
    }

    @Test
    void listSkillsReturnsEmptyWhenNoSkillsAvailable() {
        SkillQueryService service = new SkillQueryService(skillProvider, new SkillProperties("./skills", List.of()));
        when(skillProvider.findAll()).thenReturn(List.of());

        assertThat(service.listSkills()).isEmpty();
    }
}
