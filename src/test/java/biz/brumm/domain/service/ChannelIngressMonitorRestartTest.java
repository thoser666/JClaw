package biz.brumm.domain.service;

import biz.brumm.domain.model.Channel;
import biz.brumm.domain.model.ChannelMessage;
import biz.brumm.domain.model.ChannelType;
import biz.brumm.domain.model.MessageDirection;
import biz.brumm.infrastructure.adapter.out.persistence.JdbcIngressCursorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikation der durability über den Neustart hinweg: Zwei getrennte
 * Monitor-Instanzen teilen sich denselben persistenten Store – die zweite
 * Instanz („nach Neustart") darf keine erneut zugestellten Items übernehmen.
 */
@SpringBootTest
@Transactional
class ChannelIngressMonitorRestartTest {

    @Autowired
    private JdbcIngressCursorStore cursorStore;

    private Channel channel() {
        return new Channel("ch-restart", "X Bot", ChannelType.X, true,
                Map.of("token", "abc"), Instant.now(), Instant.now());
    }

    @Test
    void itemsAreNotDeliveredAgainAfterMonitorRestart() throws Exception {
        ChannelIngressMonitor monitorA = new ChannelIngressMonitor(cursorStore);
        ChannelIngressMonitor monitorB = new ChannelIngressMonitor(cursorStore);
        List<ChannelMessage> inboxA = new ArrayList<>();
        List<ChannelMessage> inboxB = new ArrayList<>();

        ChannelIngressMonitor.PollResult firstRun = monitorA.pollOnce(
                channel(),
                since -> List.of(msg("u1", "e1"), msg("u2", "e2")),
                inboxA::add, null, Instant.now());

        ChannelIngressMonitor.PollResult secondRun = monitorB.pollOnce(
                channel(),
                since -> List.of(msg("u1", "e1"), msg("u2", "e2")),
                inboxB::add, null, Instant.now());

        assertThat(firstRun.newItems()).isEqualTo(2);
        assertThat(inboxA).extracting(ChannelMessage::externalId).containsExactly("e1", "e2");
        assertThat(secondRun.newItems()).isZero();
        assertThat(secondRun.skipped()).isEqualTo(2);
        assertThat(inboxB).isEmpty();
        assertThat(cursorStore.countItems(channel().id())).isEqualTo(2);
    }

    @Test
    void subsequentlyAdmittedItemsAreDeliveredEvenAfterRestart() throws Exception {
        ChannelIngressMonitor monitorA = new ChannelIngressMonitor(cursorStore);
        ChannelIngressMonitor monitorB = new ChannelIngressMonitor(cursorStore);
        List<ChannelMessage> inbox = new ArrayList<>();

        monitorA.pollOnce(channel(), since -> List.of(msg("u1", "e1")), inbox::add, null, Instant.now());

        ChannelIngressMonitor.PollResult result = monitorB.pollOnce(
                channel(), since -> List.of(msg("u1", "e1"), msg("u2", "e2")), inbox::add, null, Instant.now());

        assertThat(result.newItems()).isEqualTo(1);
        assertThat(inbox).extracting(ChannelMessage::externalId).containsExactly("e1", "e2");
    }

    private static ChannelMessage msg(String id, String externalId) {
        return new ChannelMessage(id, "ch-restart", externalId,
                MessageDirection.INBOUND, "content",
                "sender-1", "Sender", null, null, Instant.now());
    }
}