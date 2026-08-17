package biz.brumm.domain.service;

import biz.brumm.config.SessionProperties;
import biz.brumm.domain.model.Session;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.domain.port.out.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionStore sessionStore;

    @Mock
    private ConversationStore conversationStore;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(sessionStore, conversationStore,
                new SessionProperties("none", 4, 60));
    }

    @Test
    void findSessionReturnsEmptyForNull() {
        assertThat(service.findSession(null)).isEmpty();
    }

    @Test
    void findSessionReturnsEmptyForBlank() {
        assertThat(service.findSession("  ")).isEmpty();
    }

    @Test
    void findSessionDelegatesToStore() {
        Session session = new Session("s1", null, Instant.now(), Instant.now(), Instant.now());
        when(sessionStore.findById("s1")).thenReturn(Optional.of(session));

        assertThat(service.findSession("s1")).contains(session);
    }

    @Test
    void createSessionSavesNewSession() {
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Session created = service.createSession("new-id");

        assertThat(created.sessionId()).isEqualTo("new-id");
        assertThat(created.displayName()).isNull();
        verify(sessionStore).save(any());
    }

    @Test
    void touchSessionSetsDisplayNameFromPrompt() {
        Session existing = new Session("s1", null, Instant.now(), Instant.now(), Instant.now());
        when(sessionStore.findById("s1")).thenReturn(Optional.of(existing));
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Session touched = service.touchSession("s1", "Hallo Welt");

        assertThat(touched.displayName()).isEqualTo("Hallo Welt");
    }

    @Test
    void touchSessionTruncatesLongDisplayName() {
        Session existing = new Session("s1", null, Instant.now(), Instant.now(), Instant.now());
        when(sessionStore.findById("s1")).thenReturn(Optional.of(existing));
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String longPrompt = "A".repeat(100);
        Session touched = service.touchSession("s1", longPrompt);

        assertThat(touched.displayName()).hasSize(60);
    }

    @Test
    void touchSessionDoesNotOverwriteExistingDisplayName() {
        Session existing = new Session("s1", "Existing Title", Instant.now(), Instant.now(), Instant.now());
        when(sessionStore.findById("s1")).thenReturn(Optional.of(existing));
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Session touched = service.touchSession("s1", "New Title");

        assertThat(touched.displayName()).isEqualTo("Existing Title");
    }

    @Test
    void touchSessionCreatesSessionIfNotFound() {
        when(sessionStore.findById("missing")).thenReturn(Optional.empty());
        when(sessionStore.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Session touched = service.touchSession("missing", "prompt");

        assertThat(touched.sessionId()).isEqualTo("missing");
    }

    @Test
    void shouldResetReturnsFalseForNoneMode() {
        Session session = new Session("s1", null, Instant.now(), Instant.now(), Instant.now());
        assertThat(service.shouldReset(session)).isFalse();
    }

    @Test
    void shouldResetReturnsFalseForDailyWhenSameDay() {
        SessionService dailyService = new SessionService(sessionStore, conversationStore,
                new SessionProperties("daily", 4, 60));
        Session session = new Session("s1", null, Instant.now(), Instant.now(), Instant.now());

        assertThat(dailyService.shouldReset(session)).isFalse();
    }

    @Test
    void shouldResetReturnsTrueForDailyWhenOlderThanBoundary() {
        SessionService dailyService = new SessionService(sessionStore, conversationStore,
                new SessionProperties("daily", 0, 60));
        Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
        Session session = new Session("s1", null, twoDaysAgo, twoDaysAgo, twoDaysAgo);

        assertThat(dailyService.shouldReset(session)).isTrue();
    }

    @Test
    void shouldResetReturnsTrueForIdleWhenExpired() {
        SessionService idleService = new SessionService(sessionStore, conversationStore,
                new SessionProperties("idle", 4, 1));
        Instant twoMinutesAgo = Instant.now().minus(2, ChronoUnit.MINUTES);
        Session session = new Session("s1", null, Instant.now(), twoMinutesAgo, twoMinutesAgo);

        assertThat(idleService.shouldReset(session)).isTrue();
    }

    @Test
    void shouldResetReturnsFalseForIdleWhenNotExpired() {
        SessionService idleService = new SessionService(sessionStore, conversationStore,
                new SessionProperties("idle", 4, 60));
        Session session = new Session("s1", null, Instant.now(), Instant.now(), Instant.now());

        assertThat(idleService.shouldReset(session)).isFalse();
    }

    @Test
    void listSessionsDelegatesToStore() {
        when(sessionStore.findAll()).thenReturn(List.of());
        assertThat(service.listSessions()).isEmpty();
    }

    @Test
    void deleteSessionDeletesBothMetadataAndMessages() {
        service.deleteSession("s1");

        verify(sessionStore).deleteById("s1");
        verify(conversationStore).deleteByContextId("s1");
    }
}
