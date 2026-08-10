package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.SkillOverview;
import biz.brumm.domain.port.in.ListSkillsUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SkillRestController.class)
class SkillRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListSkillsUseCase listSkillsUseCase;

    @Test
    void getSkillsReturnsOverviews() throws Exception {
        when(listSkillsUseCase.listSkills()).thenReturn(List.of(
                new SkillOverview("code-review", "Prueft PRs.", true),
                new SkillOverview("docs", "Schreibt Doku.", false)));

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("code-review"))
                .andExpect(jsonPath("$[0].description").value("Prueft PRs."))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].enabled").value(false));
    }

    @Test
    void getSkillsReturnsEmptyListWhenNoSkills() throws Exception {
        when(listSkillsUseCase.listSkills()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/skills"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
