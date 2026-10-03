package biz.brumm.domain.service;

import biz.brumm.domain.model.FollowUp;
import biz.brumm.domain.port.out.FollowUpQueueStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowUpQueueServiceTest {

    private final FollowUpQueueStore store = mock(FollowUpQueueStore.class);
    private final FollowUpExecutor executor = mock(FollowUpExecutor.class);
    private final FollowUpQueueService service = new FollowUpQueueService(store, executor);

    private FollowUp pending(String id) {
        return new FollowUp(id, "s1", "Fasse zusammen", Instant.parse("2026-09-01T08:00:00Z"), null);
    }

    @Test
    void enqueuePersistsFollowUp() {
        when(store.enqueue(any(FollowUp.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FollowUp enqueued = service.enqueue("s1", "Fasse zusammen");

        verify(store).enqueue(enqueued);
        assertThat(enqueued.id()).isNotBlank();
        assertThat(enqueued.deliveredAt()).isNull();
    }

    @Test
    void enqueueRejectsBlankPrompt() {
        assertThatThrownBy(() -> service.enqueue("s1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).enqueue(any(FollowUp.class));
    }

    @Test
    void pendingReturnsOnlyOpenFollowUps() {
        when(store.findPendingBySessionId("s1")).thenReturn(List.of(pending("f1")));

        assertThat(service.pending("s1")).extracting(FollowUp::id).containsExactly("f1");
    }

    @Test
    void drainExecutesAndMarksDelivered() {
        FollowUp f1 = pending("f1");
        when(store.findPendingBySessionId("s1")).thenReturn(List.of(f1));
        when(store.markDelivered(eq("f1"), any(Instant.class))).thenReturn(f1.withDelivered(Instant.now()));

        List<FollowUp> delivered = service.drain("s1");

        verify(executor).execute("s1", "Fasse zusammen");
        verify(store).markDelivered(eq("f1"), any(Instant.class));
        assertThat(delivered).extracting(FollowUp::id).containsExactly("f1");
    }

    @Test
    void drainKeepsPendingOnExecutorFailure() {
        FollowUp f1 = pending("f1");
        when(store.findPendingBySessionId("s1")).thenReturn(List.of(f1));
        doThrow(new RuntimeException("Agent fehlgeschlagen"))
                .when(executor).execute("s1", "Fasse zusammen");

        List<FollowUp> delivered = service.drain("s1");

        verify(store, never()).markDelivered(eq("f1"), any(Instant.class));
        assertThat(delivered).isEmpty();
    }

    @Test
    void drainContinuesAfterFailedFollowUp() {
        FollowUp f1 = pending("f1");
        FollowUp f2 = new FollowUp("f2", "s1", "Anderes Thema", Instant.parse("2026-09-01T08:00:00Z"), null);
        when(store.findPendingBySessionId("s1")).thenReturn(List.of(f1, f2));
        doThrow(new RuntimeException("fehlgeschlagen"))
                .when(executor).execute("s1", "Fasse zusammen");
        when(store.markDelivered(eq("f2"), any(Instant.class))).thenReturn(f2.withDelivered(Instant.now()));

        List<FollowUp> delivered = service.drain("s1");

        verify(store, never()).markDelivered(eq("f1"), any(Instant.class));
        assertThat(delivered).extracting(FollowUp::id).containsExactly("f2");
    }

    @Test
    void cancelDeletesFollowUp() {
        service.cancel("f1");

        verify(store).deleteById("f1");
    }
}