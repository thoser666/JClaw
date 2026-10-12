package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.model.GoalStatus;
import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.service.GoalOperationException;
import biz.brumm.domain.service.GoalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalModelToolTest {

    private final GoalService goalService = mock(GoalService.class);
    private final GoalModelTool tool = new GoalModelTool(goalService);

    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    private static SessionGoal activeGoal() {
        return new SessionGoal("s1", "Schreibe README", GoalStatus.ACTIVE, null, null, NOW, null);
    }

    @Test
    void getGoalReturnsSnapshotWhenPresent() {
        when(goalService.getGoal("s1")).thenReturn(Optional.of(activeGoal()));

        String result = tool.getGoal("s1");

        assertThat(result).contains("\"sessionId\":\"s1\"", "Schreibe README", "\"status\":\"active\"");
        assertThat(result).contains("\"tokenUsage\":null");
    }

    @Test
    void getGoalReturnsNoGoalMarkerWhenMissing() {
        when(goalService.getGoal("s1")).thenReturn(Optional.empty());

        String result = tool.getGoal("s1");

        assertThat(result).contains("\"sessionId\":\"s1\"", "\"goal\":null");
    }

    @Test
    void getGoalRejectsBlankSessionId() {
        String result = tool.getGoal("  ");

        assertThat(result).contains("\"error\"", "Session-ID");
        verify(goalService, never()).getGoal(anyString());
    }

    @Test
    void createGoalReturnsCreatedGoal() {
        when(goalService.startGoal(eq("s1"), anyString(), any())).thenAnswer(invocation -> activeGoal());

        String result = tool.createGoal("s1", "Schreibe README", null);

        assertThat(result).contains("\"status\":\"created\"", "\"status\":\"active\"", "Schreibe README");
        verify(goalService).startGoal("s1", "Schreibe README", null);
    }

    @Test
    void createGoalDelegatesTokenBudget() {
        when(goalService.startGoal(eq("s1"), anyString(), any())).thenAnswer(invocation -> activeGoal());

        tool.createGoal("s1", "Budget-Ziel", 100_000L);

        verify(goalService).startGoal("s1", "Budget-Ziel", 100_000L);
    }

    @Test
    void createGoalRejectsBlankObjective() {
        when(goalService.startGoal(eq("s1"), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Objective darf nicht leer sein."));

        String result = tool.createGoal("s1", "  ", null);

        assertThat(result).contains("\"error\"", "Objective darf nicht leer sein.");
    }

    @Test
    void createGoalReturnsErrorWhenGoalAlreadyExists() {
        when(goalService.startGoal(eq("s1"), anyString(), any()))
                .thenThrow(new GoalOperationException(
                        "Goal error: Es existiert bereits ein Ziel für diese Session.", false));

        String result = tool.createGoal("s1", "Neues Ziel", null);

        assertThat(result).contains("\"error\"", "bereits ein Ziel");
    }

    @Test
    void createGoalReturnsErrorOnNonPositiveBudget() {
        when(goalService.startGoal(eq("s1"), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Token-Budget muss positiv sein."));

        String result = tool.createGoal("s1", "Ziel", 0L);

        assertThat(result).contains("\"error\"", "Token-Budget muss positiv sein.");
    }

    @Test
    void updateGoalMarksComplete() {
        when(goalService.completeGoal("s1", "fertig"))
                .thenReturn(new SessionGoal("s1", "Ziel", GoalStatus.COMPLETE, "fertig", null, NOW, NOW));

        String result = tool.updateGoal("s1", "complete", "fertig");

        assertThat(result).contains("\"status\":\"updated\"", "\"completedAt\"");
        assertThat(result).contains("\"status\":\"complete\"");
        verify(goalService).completeGoal("s1", "fertig");
    }

    @Test
    void updateGoalMarksBlockedWithNote() {
        when(goalService.blockGoal("s1", "Blocker"))
                .thenReturn(new SessionGoal("s1", "Ziel", GoalStatus.BLOCKED, "Blocker", null, NOW, null));

        String result = tool.updateGoal("s1", "blocked", "Blocker");

        assertThat(result).contains("\"status\":\"updated\"", "\"status\":\"blocked\"", "Blocker");
        verify(goalService).blockGoal("s1", "Blocker");
    }

    @Test
    void updateGoalRejectsUnknownStatus() {
        String result = tool.updateGoal("s1", "paused", null);

        assertThat(result).contains("\"error\"", "complete", "blocked");
        verify(goalService, never()).completeGoal(anyString(), any());
        verify(goalService, never()).blockGoal(anyString(), any());
    }

    @Test
    void updateGoalReturnsErrorWhenMissing() {
        when(goalService.completeGoal("s1", null))
                .thenThrow(new GoalOperationException("Für diese Session existiert kein Ziel.", true));

        String result = tool.updateGoal("s1", "complete", null);

        assertThat(result).contains("\"error\"", "kein Ziel");
    }

    @Test
    void updateGoalRejectsBlankSessionId() {
        String result = tool.updateGoal("  ", "complete", null);

        assertThat(result).contains("\"error\"", "Session-ID");
        verify(goalService, never()).completeGoal(anyString(), any());
        verify(goalService, never()).blockGoal(anyString(), any());
    }

    @Test
    void updateGoalDelegatesBlankStatusAsError() {
        String result = tool.updateGoal("s1", null, null);

        assertThat(result).contains("\"error\"");
        verify(goalService, never()).completeGoal(anyString(), any());
        verify(goalService, never()).blockGoal(anyString(), any());
    }
}