package biz.brumm.domain.service;

import biz.brumm.config.SkillProperties;
import biz.brumm.domain.model.Skill;
import biz.brumm.domain.model.SkillOverview;
import biz.brumm.domain.port.in.ListSkillsUseCase;
import biz.brumm.domain.port.out.SkillProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SkillQueryService implements ListSkillsUseCase {

    private final SkillProvider skillProvider;
    private final SkillProperties skillProperties;

    public SkillQueryService(SkillProvider skillProvider, SkillProperties skillProperties) {
        this.skillProvider = skillProvider;
        this.skillProperties = skillProperties;
    }

    @Override
    public List<SkillOverview> listSkills() {
        return skillProvider.findAll().stream()
                .map(this::toOverview)
                .toList();
    }

    private SkillOverview toOverview(Skill skill) {
        return new SkillOverview(skill.name(), skill.description(), skillProperties.enabled().contains(skill.name()));
    }
}
