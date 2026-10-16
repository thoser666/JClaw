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
    void indexContainsThemeToggle() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"theme-toggle\"")))
                .andExpect(content().string(containsString("id=\"theme-icon\"")))
                .andExpect(content().string(containsString("id=\"theme-label\"")));
    }

    @Test
    void stylesheetIsServed() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andExpect(content().string(containsString("--accent:")));
    }

    @Test
    void stylesheetContainsDarkTheme() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".dark")))
                .andExpect(content().string(containsString("--bg: #0f1117")))
                .andExpect(content().string(containsString("--surface: #1a1d27")));
    }

    @Test
    void scriptIsServed() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("renderSkills")));
    }

    @Test
    void scriptContainsThemeToggle() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("toggleTheme")))
                .andExpect(content().string(containsString("localStorage")))
                .andExpect(content().string(containsString("jclaw-theme")));
    }

    @Test
    void unknownResourceReturnsNotFound() throws Exception {
        mockMvc.perform(get("/js/gibt-es-nicht.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void indexContainsGoalComposer() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-panel=\"goals\"")))
                .andExpect(content().string(containsString("id=\"panel-goals\"")))
                .andExpect(content().string(containsString("id=\"goal-form\"")))
                .andExpect(content().string(containsString("id=\"goal-session\"")))
                .andExpect(content().string(containsString("id=\"goal-objective\"")))
                .andExpect(content().string(containsString("id=\"goal-token-budget\"")))
                .andExpect(content().string(containsString("id=\"goal-note\"")))
                .andExpect(content().string(containsString("id=\"goal-status\"")))
                .andExpect(content().string(containsString("id=\"goal-view\"")));
    }

    @Test
    void indexContainsGoalActionButtons() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"goal-load\"")))
                .andExpect(content().string(containsString("id=\"goal-start\"")))
                .andExpect(content().string(containsString("id=\"goal-edit\"")))
                .andExpect(content().string(containsString("id=\"goal-pause\"")))
                .andExpect(content().string(containsString("id=\"goal-resume\"")))
                .andExpect(content().string(containsString("id=\"goal-block\"")))
                .andExpect(content().string(containsString("id=\"goal-complete\"")))
                .andExpect(content().string(containsString("id=\"goal-clear\"")));
    }

    @Test
    void scriptContainsGoalComposerEndpoints() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"/goal/start\"")))
                .andExpect(content().string(containsString("\"/goal/edit\"")))
                .andExpect(content().string(containsString("\"/goal/pause\"")))
                .andExpect(content().string(containsString("\"/goal/resume\"")))
                .andExpect(content().string(containsString("\"/goal/block\"")))
                .andExpect(content().string(containsString("\"/goal/complete\"")))
                .andExpect(content().string(containsString("method: \"DELETE\"")));
    }

    @Test
    void scriptRendersGoalStateAndHandlesMissingGoal() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("function renderGoal")))
                .andExpect(content().string(containsString("goalBadgeType")))
                .andExpect(content().string(containsString("HTTP 404")))
                .andExpect(content().string(containsString("Kein Ziel")))
                .andExpect(content().string(containsString("Session vorhanden.")));
    }

    @Test
    void scriptOffersGoalShortcutOnSessionTiles() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("goalSessionShortcut")));
    }

    @Test
    void indexContainsBackgroundComposerControls() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"task-background\"")))
                .andExpect(content().string(containsString("id=\"notification-badge\"")))
                .andExpect(content().string(containsString("id=\"notification-count\"")));
    }

    @Test
    void scriptStartsBackgroundTasksWithoutPageSwitch() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"/api/v1/background-tasks/\"")))
                .andExpect(content().string(containsString("\"#task-background\"")))
                .andExpect(content().string(containsString("Im Hintergrund")));
    }

    @Test
    void scriptSubscribesToCompletionEvents() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("new EventSource(")))
                .andExpect(content().string(containsString("\"/events\"")))
                .andExpect(content().string(containsString("addEventListener(\"completion\"")))
                .andExpect(content().string(containsString("source.close()")));
    }

    @Test
    void scriptShowsNotificationBadge() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bumpNotification")))
                .andExpect(content().string(containsString("\"#notification-badge\"")))
                .andExpect(content().string(containsString("\"#notification-count\"")))
                .andExpect(content().string(containsString("showPanel(\"agent\")")));
    }

    @Test
    void stylesheetContainsNotificationBadge() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".notification-badge")))
                .andExpect(content().string(containsString(".dark .notification-badge")));
    }
}
