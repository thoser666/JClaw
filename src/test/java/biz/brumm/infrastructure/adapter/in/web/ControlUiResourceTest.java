package biz.brumm.infrastructure.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class ControlUiResourceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void welcomePageForwardsToControlUi() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void indexServesControlUiShell() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("JClaw Control")))
                .andExpect(content().string(containsString("data-panel=\"agent\"")))
                .andExpect(content().string(containsString("id=\"task-form\"")))
                .andExpect(content().string(containsString("id=\"conversation-list\"")))
                .andExpect(content().string(containsString("id=\"skills-list\"")))
                .andExpect(content().string(containsString("id=\"plugins-list\"")))
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("/js/app.js")));
    }

    @Test
    void stylesheetIsServed() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("--accent:")));
    }

    @Test
    void scriptIsServed() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("renderSkills")));
    }

    @Test
    void unknownResourceReturnsNotFound() throws Exception {
        mockMvc.perform(get("/js/gibt-es-nicht.js"))
                .andExpect(status().isNotFound());
    }
}
