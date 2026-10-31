package biz.brumm.domain.service;

import biz.brumm.domain.model.MemoryDocument;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryRetrievalServiceTest {

    private ObjectProvider<EmbeddingModel> provider(EmbeddingModel model) {
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @Test
    void indexAndSearchReturnsMostSimilarDocument() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        // doc1 vector ~ query vector (cosine ~1), doc2 orthogonal
        when(model.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String t = inv.getArgument(0);
                    if (t.contains("Äpfel")) return new float[]{1.0f, 0.0f};
                    if (t.contains("Bananen")) return new float[]{0.0f, 1.0f};
                    // query
                    return new float[]{1.0f, 0.0f};
                });

        MemoryRetrievalService service = new MemoryRetrievalService(provider(model));

        MemoryDocument d1 = new MemoryDocument("ctx-1", "Äpfel", Instant.now(), List.of(), "Äpfel sind rot");
        MemoryDocument d2 = new MemoryDocument("ctx-2", "Bananen", Instant.now(), List.of(), "Bananen sind gelb");
        service.index(d1);
        service.index(d2);

        assertThat(service.size()).isEqualTo(2);
        List<MemoryDocument> result = service.search("Äpfel gesucht", 1);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).conversationId()).isEqualTo("ctx-1");
    }

    @Test
    void searchWithoutModelReturnsEmpty() {
        MemoryRetrievalService service = new MemoryRetrievalService(provider(null));
        MemoryDocument d = new MemoryDocument("ctx-1", "T", Instant.now(), List.of(), "c");
        service.index(d);
        assertThat(service.size()).isEqualTo(0);
        assertThat(service.search("q", 5)).isEmpty();
    }

    @Test
    void blankQueryReturnsEmpty() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        MemoryRetrievalService service = new MemoryRetrievalService(provider(model));
        assertThat(service.search("   ", 5)).isEmpty();
        assertThat(service.search(null, 5)).isEmpty();
    }

    @Test
    void removeDropsDocument() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(org.mockito.ArgumentMatchers.anyString())).thenReturn(new float[]{1.0f, 0.0f});
        MemoryRetrievalService service = new MemoryRetrievalService(provider(model));
        MemoryDocument d = new MemoryDocument("ctx-1", "T", Instant.now(), List.of(), "c");
        service.index(d);
        assertThat(service.size()).isEqualTo(1);
        service.remove("ctx-1");
        assertThat(service.size()).isEqualTo(0);
    }
}