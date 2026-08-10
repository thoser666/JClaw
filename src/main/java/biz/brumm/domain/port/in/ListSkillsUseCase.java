package biz.brumm.domain.port.in;

import biz.brumm.domain.model.SkillOverview;

import java.util.List;

public interface ListSkillsUseCase {
    List<SkillOverview> listSkills();
}
