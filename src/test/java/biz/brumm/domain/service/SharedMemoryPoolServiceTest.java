package biz.brumm.domain.service;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.model.SharedMemoryPool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SharedMemoryPoolServiceTest {

    private ObjectProvider<EmbeddingModel> provider(EmbeddingModel model) {
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(model);
        return p;
    }

    @Test
    void createAndListPools() {
        SharedMemoryPoolService svc = new SharedMemoryPoolService(provider(null));
        SharedMemoryPool p = svc.createPool("p1", "Pool 1", "desc");
        assertThat(p.id()).isEqualTo("p1");
        assertThat(svc.listPools()).hasSize(1);
        assertThat(svc.getPool("p1")).isNotNull();
        assertThat(svc.getPool("nope")).isNull();
    }

    @Test
    void deletePool() {
        SharedMemoryPoolService svc = new SharedMemoryPoolService(provider(null));
        svc.createPool("p1", "Pool 1", "");
        assertThat(svc.deletePool("p1")).isTrue();
        assertThat(svc.deletePool("p1")).isFalse();
        assertThat(svc.listPools()).isEmpty();
    }

    @Test
    void addToPoolAndSearchReturnsMostSimilar() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String t = inv.getArgument(0);
                    if (t.contains("Äpfel")) return new float[]{1.0f, 0.0f};
                    if (t.contains("Bananen")) return new float[]{0.0f, 1.0f};
                    return new float[]{1.0f, 0.0f};
                });
        SharedMemoryPoolService svc = new SharedMemoryPoolService(provider(model));
        svc.createPool("p1", "Pool", "");
        MemoryDocument d1 = new MemoryDocument("ctx-1", "Äpfel", Instant.now(), List.of(), "Äpfel sind rot");
        MemoryDocument d2 = new MemoryDocument("ctx-2", "Bananen", Instant.now(), List.of(), "Bananen sind gelb");
        svc.addToPool("p1", d1);
        svc.addToPool("p1", d2);
        assertThat(svc.poolSize("p1")).isEqualTo(2);

        List<MemoryDocument> res = svc.searchPool("p1", "Äpfel gesucht", 1);
        assertThat(res).hasSize(1);
        assertThat(res.get(0).conversationId()).isEqualTo("ctx-1");
    }

    @Test
    void searchWithoutModelReturnsEmpty() {
        SharedMemoryPoolService svc = new SharedMemoryPoolService(provider(null));
        svc.createPool("p1", "Pool", "");
        assertThat(svc.searchPool("p1", "q", 5)).isEmpty();
    }

    @Test
    void searchUnknownPoolReturnsEmpty() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        SharedMemoryPoolService svc = new SharedMemoryPoolService(provider(model));
        assertThat(svc.searchPool("nope", "q", 5)).isEmpty();
    }

    @Test
    void addToPoolWithoutModelIsNoOp() {
        SharedMemoryPoolService svc = new SharedMemoryPoolService(provider(null));
        svc.createPool("p1", "Pool", "");
        MemoryDocument d = new MemoryDocument("ctx-1", "T", Instant.now(), List.of(), "c");
        svc.addToPool("p1", d);
        assertThat(svc.poolSize("p1")).isEqualTo(0);
    }
}