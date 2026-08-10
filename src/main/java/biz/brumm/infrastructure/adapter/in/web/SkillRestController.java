package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.SkillOverview;
import biz.brumm.domain.port.in.ListSkillsUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillRestController {

    private final ListSkillsUseCase listSkillsUseCase;

    public SkillRestController(ListSkillsUseCase listSkillsUseCase) {
        this.listSkillsUseCase = listSkillsUseCase;
    }

    @GetMapping
    public ResponseEntity<List<SkillOverview>> listSkills() {
        return ResponseEntity.ok(listSkillsUseCase.listSkills());
    }
}
