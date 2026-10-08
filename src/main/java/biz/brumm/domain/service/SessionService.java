package biz.brumm.domain.service;

import biz.brumm.config.SessionProperties;
import biz.brumm.domain.model.Session;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.domain.port.out.PluginHookDispatcher;
import biz.brumm.domain.port.out.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final int MAX_DISPLAY_NAME_LENGTH = 60;

    private final SessionStore sessionStore;
    private final ConversationStore conversationStore;
    private final SessionProperties properties;
    private final CredentialLeakGuard credentialLeakGuard;
    private final PluginHookDispatcher pluginHooks;

    public SessionService(SessionStore sessionStore, ConversationStore conversationStore,
                          SessionProperties properties, CredentialLeakGuard credentialLeakGuard,
                          PluginHookDispatcher pluginHooks) {
        this.sessionStore = sessionStore;
        this.conversationStore = conversationStore;
        this.properties = properties;
        this.credentialLeakGuard = credentialLeakGuard;
        this.pluginHooks = pluginHooks;
    }

    public Optional<Session> findSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return sessionStore.findById(sessionId).map(this::sanitize);
    }

    public Session createSession(String sessionId) {
        Instant now = Instant.now();
        Session session = new Session(sessionId, null, now, now, now);
        Session saved = sessionStore.save(session);
        // session_start (OpenClaw): beobachtet nach dem Anlegen der Session.
        pluginHooks.dispatch("session_start",
                Map.of("sessionId", sessionId, "reason", "new", "startedAt", saved.sessionStartedAt().toString()));
        return saved;
    }

    public Session touchSession(String sessionId, String prompt) {
        Optional<Session> existing = sessionStore.findById(sessionId);
        if (existing.isEmpty()) {
            return createSession(sessionId);
        }
        Session session = existing.get();
        String displayName = session.displayName();
        if (displayName == null && prompt != null && !prompt.isBlank()) {
            displayName = credentialLeakGuard.redact(deriveDisplayName(prompt));
        }
        Instant now = Instant.now();
        Session updated = new Session(session.sessionId(), displayName, session.group(),
                session.sessionStartedAt(), now, now);
        return sessionStore.save(updated);
    }

    public Session updateSessionGroup(String sessionId, String group) {
        Optional<Session> existing = sessionStore.findById(sessionId);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("Session nicht gefunden: " + sessionId);
        }
        Session session = existing.get();
        Instant now = Instant.now();
        Session updated = new Session(session.sessionId(), session.displayName(), group,
                session.sessionStartedAt(), session.lastInteractionAt(), now);
        return sessionStore.save(updated);
    }

    public List<Session> listSessionsByGroup(String group) {
        return sessionStore.findByGroup(group).stream().map(this::sanitize).toList();
    }

    public boolean shouldReset(Session session) {
        if ("daily".equals(properties.resetMode())) {
            return isDailyResetNeeded(session.sessionStartedAt());
        }
        if ("idle".equals(properties.resetMode())) {
            return isIdleResetNeeded(session.lastInteractionAt());
        }
        return false;
    }

    public List<Session> listSessions() {
        return sessionStore.findAll().stream().map(this::sanitize).toList();
    }

    public void deleteSession(String sessionId) {
        conversationStore.deleteByContextId(sessionId);
        sessionStore.deleteById(sessionId);
        // session_end (OpenClaw): beobachtet nach dem Entfernen der Session.
        pluginHooks.dispatch("session_end", Map.of("sessionId", sessionId, "reason", "deleted"));
    }

    private Session sanitize(Session session) {
        String displayName = session.displayName();
        if (displayName == null || displayName.isBlank()) {
            return session;
        }
        String redacted = credentialLeakGuard.redact(displayName);
        if (redacted.equals(displayName)) {
            return session;
        }
        return new Session(session.sessionId(), redacted, session.group(),
                session.sessionStartedAt(), session.lastInteractionAt(), session.updatedAt());
    }

    private boolean isDailyResetNeeded(Instant sessionStartedAt) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime started = sessionStartedAt.atZone(zone);
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        ZonedDateTime boundary = today.atTime(LocalTime.of(properties.resetAtHour(), 0)).atZone(zone);
        if (now.isBefore(boundary)) {
            return started.toLocalDate().isBefore(today);
        }
        return started.isBefore(boundary);
    }

    private boolean isIdleResetNeeded(Instant lastInteractionAt) {
        long elapsedSeconds = Instant.now().getEpochSecond() - lastInteractionAt.getEpochSecond();
        return elapsedSeconds > properties.resetIdleMinutes() * 60L;
    }

    private String deriveDisplayName(String prompt) {
        String trimmed = prompt.trim();
        if (trimmed.length() <= MAX_DISPLAY_NAME_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_DISPLAY_NAME_LENGTH);
    }
}
