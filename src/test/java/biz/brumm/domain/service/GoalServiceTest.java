package biz.brumm.domain.service;

import biz.brumm.domain.model.GoalStatus;
import biz.brumm.domain.model.SessionGoal;
import biz.brumm.domain.port.out.GoalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalServiceTest {

    private final GoalStore goalStore = mock(GoalStore.class);
    private final GoalService goalService = new GoalService(goalStore);

    @BeforeEach
    void setUpStub() {
        when(goalStore.save(any(SessionGoal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(goalStore.findBySessionId("s1")).thenReturn(Optional.empty());
    }

    private void givenExistingGoal(String sessionId, SessionGoal goal) {
        when(goalStore.findBySessionId(sessionId)).thenReturn(Optional.of(goal));
    }

    private SessionGoal activeGoal(String sessionId) {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");
        return new SessionGoal(sessionId, "Ziel", GoalStatus.ACTIVE, null, null, now, null);
    }

    @Test
    void startGoalCreatesActiveGoal() {
        SessionGoal saved = goalService.startGoal("s1", "Schreibe README", null);

        assertThat(saved.sessionId()).isEqualTo("s1");
        assertThat(saved.objective()).isEqualTo("Schreibe README");
        assertThat(saved.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(saved.completedAt()).isNull();
        verify(goalStore).save(any(SessionGoal.class));
    }

    @Test
    void startGoalStoresTokenBudget() {
        SessionGoal saved = goalService.startGoal("s1", "Budget-Ziel", 100_000L);

        assertThat(saved.tokenBudget()).isEqualTo(100_000L);
    }

    @Test
    void startGoalThrowsWhenGoalAlreadyExists() {
        givenExistingGoal("s1", activeGoal("s1"));

        assertThatThrownBy(() -> goalService.startGoal("s1", "Neues Ziel", null))
                .isInstanceOf(GoalOperationException.class)
                .extracting(thrown -> ((GoalOperationException) thrown).isNotFound())
                .isEqualTo(false);
    }

    @Test
    void startGoalRejectsBlankObjective() {
        assertThatThrownBy(() -> goalService.startGoal("s1", "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(goalStore, never()).save(any(SessionGoal.class));
    }

    @Test
    void startGoalRejectsNonPositiveTokenBudget() {
        assertThatThrownBy(() -> goalService.startGoal("s1", "Ziel", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewordGoalUpdatesObjective() {
        givenExistingGoal("s1", activeGoal("s1"));

        SessionGoal saved = goalService.rewordGoal("s1", "Neue Formulierung");

        assertThat(saved.objective()).isEqualTo("Neue Formulierung");
        assertThat(saved.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(saved.createdAt()).isEqualTo(activeGoal("s1").createdAt());
    }

    @Test
    void rewordGoalThrowsNotFoundWhenMissing() {
        assertThatThrownBy(() -> goalService.rewordGoal("missing", "Ziel"))
                .isInstanceOf(GoalOperationException.class)
                .extracting(thrown -> ((GoalOperationException) thrown).isNotFound())
                .isEqualTo(true);
    }

    @Test
    void pauseGoalTransitionsActiveToPaused() {
        givenExistingGoal("s1", activeGoal("s1"));

        SessionGoal saved = goalService.pauseGoal("s1", "Pause");

        assertThat(saved.status()).isEqualTo(GoalStatus.PAUSED);
        assertThat(saved.statusNote()).isEqualTo("Pause");
    }

    @Test
    void pauseGoalRejectsBlocked() {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");
        givenExistingGoal("s1", new SessionGoal("s1", "Ziel", GoalStatus.BLOCKED, null, null, now, null));

        assertThatThrownBy(() -> goalService.pauseGoal("s1", null))
                .isInstanceOf(GoalOperationException.class);
    }

    @Test
    void resumeGoalTransitionsPausedToActive() {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");
        givenExistingGoal("s1", new SessionGoal("s1", "Ziel", GoalStatus.PAUSED, null, null, now, null));

        SessionGoal saved = goalService.resumeGoal("s1", "Weiter");

        assertThat(saved.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(saved.statusNote()).isEqualTo("Weiter");
    }

    @Test
    void resumeGoalRejectsComplete() {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");
        givenExistingGoal("s1", new SessionGoal("s1", "Ziel", GoalStatus.COMPLETE, null, null, now, now));

        assertThatThrownBy(() -> goalService.resumeGoal("s1", null))
                .isInstanceOf(GoalOperationException.class);
    }

    @Test
    void blockGoalTransitionsActiveToBlocked() {
        givenExistingGoal("s1", activeGoal("s1"));

        SessionGoal saved = goalService.blockGoal("s1", "Blockiert");

        assertThat(saved.status()).isEqualTo(GoalStatus.BLOCKED);
        assertThat(saved.statusNote()).isEqualTo("Blockiert");
    }

    @Test
    void completeGoalSetsCompletedAt() {
        givenExistingGoal("s1", activeGoal("s1"));

        SessionGoal saved = goalService.completeGoal("s1", null);

        assertThat(saved.status()).isEqualTo(GoalStatus.COMPLETE);
        assertThat(saved.completedAt()).isNotNull();
    }

    @Test
    void completeGoalKeepsCompletedAtOnRecompletion() {
        Instant now = Instant.parse("2026-09-01T08:00:00Z");
        Instant completed = Instant.parse("2026-09-02T10:00:00Z");
        givenExistingGoal("s1", new SessionGoal("s1", "Ziel", GoalStatus.COMPLETE, "fertig", null, now, completed));

        SessionGoal saved = goalService.completeGoal("s1", "erneut");

        assertThat(saved.completedAt()).isEqualTo(completed);
        assertThat(saved.statusNote()).isEqualTo("erneut");
    }

    @Test
    void clearGoalDeletes() {
        goalService.clearGoal("s1");

        verify(goalStore).deleteBySessionId("s1");
    }
}