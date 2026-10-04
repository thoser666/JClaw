package biz.brumm.domain.service;

import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.model.BackgroundTask;
import biz.brumm.domain.model.BackgroundTaskStatus;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import biz.brumm.domain.port.out.BackgroundTaskStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackgroundTaskServiceTest {

    private final ExecuteTaskUseCase executeTaskUseCase = mock(ExecuteTaskUseCase.class);
    private final InMemoryBackgroundTaskStore store = new InMemoryBackgroundTaskStore();
    private final BackgroundTaskService service = new BackgroundTaskService(store, executeTaskUseCase, Runnable::run);

    private static final class InMemoryBackgroundTaskStore implements BackgroundTaskStore {
        private final Map<String, BackgroundTask> tasks = new LinkedHashMap<>();

        @Override
        public BackgroundTask save(BackgroundTask task) {
            tasks.put(task.id(), task);
            return task;
        }

        @Override
        public Optional<BackgroundTask> findById(String id) {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public List<BackgroundTask> findBySessionId(String sessionId) {
            return new ArrayList<>(tasks.values().stream()
                    .filter(task -> task.sessionId().equals(sessionId))
                    .toList());
        }

        @Override
        public int markOrphanedRunningAsFailed(String message) {
            return 0;
        }
    }

    @Test
    void startExecutesPendingTaskSynchronouslyWithDirectExecutor() {
        when(executeTaskUseCase.handle(any())).thenReturn(AgentResponse.of("Ergebnis"));

        BackgroundTask submitted = service.start("s1", "Fasse zusammen");

        BackgroundTask finished = store.findById(submitted.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(BackgroundTaskStatus.COMPLETED);
        assertThat(finished.result()).isEqualTo("Ergebnis");
        assertThat(finished.startedAt()).isNotNull();
        assertThat(finished.completedAt()).isNotNull();
    }

    @Test
    void startReturnsPendingTaskWithoutBlocking() {
        BackgroundTaskService asyncService = new BackgroundTaskService(store, executeTaskUseCase, command -> { });
        when(executeTaskUseCase.handle(any())).thenReturn(AgentResponse.of("Ergebnis"));

        BackgroundTask submitted = asyncService.start("s1", "Fasse zusammen");

        assertThat(submitted.status()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(store.findById(submitted.id()).orElseThrow().status()).isEqualTo(BackgroundTaskStatus.PENDING);
    }

    @Test
    void startRejectsBlankPrompt() {
        assertThatThrownBy(() -> service.start("s1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.tasks).isEmpty();
    }

    @Test
    void runMarksFailedWhenAgentThrows() {
        when(executeTaskUseCase.handle(any())).thenThrow(new RuntimeException("Provider nicht erreichbar"));
        BackgroundTask submitted = service.start("s1", "Prompt");

        BackgroundTask finished = store.findById(submitted.id()).orElseThrow();

        assertThat(finished.status()).isEqualTo(BackgroundTaskStatus.FAILED);
        assertThat(finished.error()).contains("Provider nicht erreichbar");
    }

    @Test
    void runSkipsAlreadyTerminalTask() {
        BackgroundTask completed = new BackgroundTask("done", "s1", "Prompt", BackgroundTaskStatus.COMPLETED,
                "altes Ergebnis", null, Instant.now(), Instant.now(), Instant.now());
        store.save(completed);

        BackgroundTask result = service.run("done");

        assertThat(result).isSameAs(completed);
        verify(executeTaskUseCase, never()).handle(any());
    }

    @Test
    void runSkipsMissingTask() {
        assertThat(service.run("missing")).isNull();
        verify(executeTaskUseCase, never()).handle(any());
    }

    @Test
    void findBySessionReturnsStoredTasks() {
        store.save(new BackgroundTask("a", "s1", "A", BackgroundTaskStatus.COMPLETED, "r", null,
                Instant.now(), Instant.now(), Instant.now()));
        store.save(new BackgroundTask("b", "s2", "B", BackgroundTaskStatus.PENDING, null, null,
                Instant.now(), null, null));

        assertThat(service.findBySession("s1")).extracting(BackgroundTask::id).containsExactly("a");
    }
}