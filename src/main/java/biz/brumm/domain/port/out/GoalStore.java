package biz.brumm.domain.port.out;

import biz.brumm.domain.model.SessionGoal;

import java.util.Optional;

public interface GoalStore {

    Optional<SessionGoal> findBySessionId(String sessionId);

    SessionGoal save(SessionGoal goal);

    void deleteBySessionId(String sessionId);
}