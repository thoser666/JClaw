package biz.brumm.domain.service;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.port.out.ConversationStore;
import biz.brumm.domain.port.out.MemoryVaultStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryVaultServiceTest {

    @Mock
    private ConversationStore conversationStore;

    @Mock
    private MemoryVaultStore vaultStore;

    @SuppressWarnings("unchecked")
    private ObjectProvider<MemoryVaultStore> provider(MemoryVaultStore store) {
        ObjectProvider<MemoryVaultStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        return provider;
    }

    private MemoryVaultService service() {
        return new MemoryVaultService(conversationStore, provider(vaultStore));
    }

    private MemoryVaultService serviceWithoutVault() {
        return new MemoryVaultService(conversationStore, provider(null));
    }

    @Test
    void syncConversationStoresMarkdownDocument() {
        when(conversationStore.findByContextId("ctx-1")).thenReturn(List.of(
                new ConversationMessage("USER", "Hallo Welt"),
                new ConversationMessage("ASSISTANT", "Hi!")));

        MemoryVaultService service = service();

        boolean written = service.syncConversation("ctx-1");

        assertThat(written).isTrue();
        ArgumentCaptor<MemoryDocument> captor = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(vaultStore).store(captor.capture());
        MemoryDocument doc = captor.getValue();
        assertThat(doc.conversationId()).isEqualTo("ctx-1");
        assertThat(doc.title()).isEqualTo("Hallo Welt");
        assertThat(doc.content()).contains("Hallo Welt").contains("Hi!");
        assertThat(doc.createdAt()).isNotNull();
    }

    @Test
    void syncConversationWithEmptyConversationDoesNotStore() {
        when(conversationStore.findByContextId("leer")).thenReturn(List.of());

        MemoryVaultService service = service();

        boolean written = service.syncConversation("leer");

        assertThat(written).isFalse();
        verify(vaultStore, never()).store(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncConversationWithBlankContextIdDoesNotStore() {
        MemoryVaultService service = service();

        assertThat(service.syncConversation(null)).isFalse();
        assertThat(service.syncConversation(" ")).isFalse();
        verify(vaultStore, never()).store(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncReturnsFalseWithoutVaultAdapter() {
        MemoryVaultService service = serviceWithoutVault();

        assertThat(service.syncConversation("ctx")).isFalse();
        assertThat(service.listDocuments()).isEmpty();
    }

    @Test
    void titleIsTruncatedToSixtyCharacters() {
        String longText = "a".repeat(100);
        when(conversationStore.findByContextId("ctx")).thenReturn(List.of(
                new ConversationMessage("USER", longText)));

        MemoryVaultService service = service();

        service.syncConversation("ctx");

        ArgumentCaptor<MemoryDocument> captor = ArgumentCaptor.forClass(MemoryDocument.class);
        verify(vaultStore, times(1)).store(captor.capture());
        assertThat(captor.getValue().title()).hasSize(61);
    }

    @Test
    void listDocumentsDelegatesToVault() {
        MemoryDocument doc = new MemoryDocument("ctx", "T", null, List.of(), "c");
        when(vaultStore.list()).thenReturn(List.of(doc));

        MemoryVaultService service = service();

        assertThat(service.listDocuments()).containsExactly(doc);
    }
}
