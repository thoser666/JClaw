package biz.brumm.domain.port.out;

import biz.brumm.domain.model.FollowUp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FollowUpQueueStore {

    FollowUp enqueue(FollowUp followUp);

    List<FollowUp> findPendingBySessionId(String sessionId);

    Optional<FollowUp> findById(String id);

    FollowUp markDelivered(String id, Instant deliveredAt);

    void deleteById(String id);
}