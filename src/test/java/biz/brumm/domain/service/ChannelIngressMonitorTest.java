package biz.brumm.domain.service;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.IngressCursor;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.domain.port.out.ChannelAdapter;
import biz.brumm.domain.port.out.IngressCursorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelIngressMonitorTest {

    @Mock
    private IngressCursorStore cursorStore;

    private ChannelIngressMonitor monitor;
    private Channel channel;

    @BeforeEach
    void setUp() {
        monitor = new ChannelIngressMonitor(cursorStore);
        channel = new Channel("ch-1", "X Bot", ChannelType.X, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
    }

    private static ChannelMessage msg(String id, String externalId) {
        return new ChannelMessage(id, "ch-1", externalId, MessageDirection.INBOUND,
                "content", "sender-1", "Sender", null, null, Instant.now());
    }

    private static ChannelAdapter.InboundMessageHandler recording(List<ChannelMessage> inbox) {
        return inbox::add;
    }

    @Test
    void pollOnceDeliversNewItemsInOrderAndAdvancesCursor() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        when(cursorStore.admitItem(eq(channel.id()), anyString(), any())).thenReturn(true);
        ChannelMessage m1 = msg("u1", "e1");
        ChannelMessage m2 = msg("u2", "e2");
        List<ChannelMessage> inbox = new ArrayList<>();
        Instant now = Instant.now();

        ChannelIngressMonitor.PollResult result =
                monitor.pollOnce(channel, since -> List.of(m1, m2), recording(inbox), null, now);

        assertThat(result).isEqualTo(new ChannelIngressMonitor.PollResult(2, 0, 2));
        assertThat(inbox).containsExactly(m1, m2);
        verify(cursorStore).saveCursor(new IngressCursor(channel.id(), "e2", now));
    }

    @Test
    void pollOnceSkipsAlreadyAdmittedItems() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        when(cursorStore.admitItem(eq(channel.id()), eq("e1"), any())).thenReturn(true);
        when(cursorStore.admitItem(eq(channel.id()), eq("e2"), any())).thenReturn(false);
        List<ChannelMessage> inbox = new ArrayList<>();
        Instant now = Instant.now();

        ChannelIngressMonitor.PollResult result = monitor.pollOnce(
                channel, since -> List.of(msg("u1", "e1"), msg("u2", "e2")), recording(inbox), null, now);

        assertThat(result.newItems()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(inbox).extracting(ChannelMessage::externalId).containsExactly("e1");
        verify(cursorStore).saveCursor(new IngressCursor(channel.id(), "e1", now));
    }

    @Test
    void pollOnceSkipsItemsWithoutExternalId() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        List<ChannelMessage> inbox = new ArrayList<>();

        ChannelIngressMonitor.PollResult result = monitor.pollOnce(
                channel, since -> List.of(msg("u1", null)), recording(inbox), null, Instant.now());

        assertThat(result.newItems()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(inbox).isEmpty();
        verify(cursorStore, never()).saveCursor(any());
    }

    @Test
    void pollOnceSkipsItemsRejectedByClaimValidator() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        when(cursorStore.admitItem(anyString(), anyString(), any())).thenReturn(true);
        List<ChannelMessage> inbox = new ArrayList<>();

        ChannelIngressMonitor.PollResult result = monitor.pollOnce(
                channel,
                since -> List.of(msg("u1", "e1"), msg("u2", "e2")),
                recording(inbox),
                item -> "e1".equals(item.externalId()),
                Instant.now());

        assertThat(result.newItems()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(inbox).extracting(ChannelMessage::externalId).containsExactly("e1");
        verify(cursorStore, never()).admitItem(eq(channel.id()), eq("e2"), any());
    }

    @Test
    void pollOncePassesStoredCursorAsSincePosition() throws Exception {
        when(cursorStore.loadCursor(channel.id()))
                .thenReturn(Optional.of(new IngressCursor(channel.id(), "e2", Instant.now())));
        AtomicReference<String> seenSince = new AtomicReference<>();

        monitor.pollOnce(channel, since -> {
            seenSince.set(since);
            return List.of();
        }, m -> {
        }, null, Instant.now());

        assertThat(seenSince.get()).isEqualTo("e2");
        verify(cursorStore, never()).saveCursor(any());
    }

    @Test
    void pollOnceUsesInitialPositionWithoutCursor() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        AtomicReference<String> seenSince = new AtomicReference<>();

        monitor.pollOnce(channel, since -> {
            seenSince.set(since);
            return List.of();
        }, m -> {
        }, null, Instant.now());

        assertThat(seenSince.get()).isEqualTo("");
    }

    @Test
    void pollOnceHandlesNullItemList() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());

        ChannelIngressMonitor.PollResult result =
                monitor.pollOnce(channel, since -> null, m -> {
                }, null, Instant.now());

        assertThat(result.total()).isZero();
        verify(cursorStore, never()).saveCursor(any());
    }

    @Test
    void startRunsLoopAndDeliversToHandlerUntilStopped() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        when(cursorStore.admitItem(eq(channel.id()), anyString(), any())).thenReturn(true);
        List<ChannelMessage> inbox = new CopyOnWriteArrayList<>();

        ChannelIngressMonitor.IngressSession session = monitor.start(
                channel, 1, since -> List.of(msg("u-" + inbox.size(), "e-" + inbox.size())), recording(inbox));

        try {
            await(() -> inbox.size() >= 2);
            assertThat(session.isRunning()).isTrue();
        } finally {
            session.stop();
        }

        await(() -> !session.isRunning());
        assertThat(inbox).isNotEmpty();
    }

    @Test
    void startLoopContinuesAfterPollerError() throws Exception {
        when(cursorStore.loadCursor(channel.id())).thenReturn(Optional.empty());
        when(cursorStore.admitItem(eq(channel.id()), anyString(), any())).thenReturn(true);
        List<ChannelMessage> inbox = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        ChannelIngressMonitor.IngressSession session = monitor.start(
                channel, 1,
                since -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new RuntimeException("temporary failure");
                    }
                    return List.of(msg("u1", "e1"));
                },
                recording(inbox));

        try {
            await(() -> !inbox.isEmpty());
        } finally {
            session.stop();
        }

        await(() -> !session.isRunning());
        assertThat(inbox).hasSize(1);
    }

    @Test
    void pruneOlderThanDelegatesToStore() {
        Instant olderThan = Instant.now().minusSeconds(60);

        monitor.pruneOlderThan(channel, olderThan);

        verify(cursorStore).pruneItemsOlderThan(channel.id(), olderThan);
    }

    private static void await(ThrowingCondition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.asBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Zeitlimit überschritten");
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean asBoolean() throws InterruptedException;
    }
}