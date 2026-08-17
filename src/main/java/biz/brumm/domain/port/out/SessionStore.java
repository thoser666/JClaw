package biz.brumm.domain.port.out;

import biz.brumm.domain.model.Session;

import java.util.List;
import java.util.Optional;

public interface SessionStore {

    Optional<Session> findById(String sessionId);

    List<Session> findAll();

    Session save(Session session);

    void deleteById(String sessionId);
}
