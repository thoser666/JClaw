package biz.brumm.domain.service;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.model.SharedMemoryPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Memory Pools (P4-15): mehrere Agents/Contexts teilen sich ein
 * Langzeit-Memory (Vektor + Kosinus-Ähnlichkeit über den dauerhaften Verlauf).
 *
 * <p>Pools und ihre Dokumente liegen in-memory (pro Pool ein eigener Vektorindex).
 * Das Embedding-Modell ist per {@code jclaw.memory.embedding.enabled=true}
 * (Deny-by-Default) aktiviert; ohne Modell bleiben Index/Search No-ops.</p>
 */
@Service
public class SharedMemoryPoolService {

    private static final Logger log = LoggerFactory.getLogger(SharedMemoryPoolService.class);

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final Map<String, SharedMemoryPool> pools = new ConcurrentHashMap<>();
    private final Map<String, PoolIndex> indices = new ConcurrentHashMap<>();

    public SharedMemoryPoolService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    public SharedMemoryPool createPool(String id, String name, String description) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Pool-id erforderlich");
        SharedMemoryPool pool = new SharedMemoryPool(id, name == null ? id : name,
                description == null ? "" : description, Instant.now());
        pools.put(id, pool);
        indices.putIfAbsent(id, new PoolIndex());
        return pool;
    }

    public List<SharedMemoryPool> listPools() {
        return new ArrayList<>(pools.values());
    }

    public SharedMemoryPool getPool(String id) {
        return pools.get(id);
    }

    public boolean deletePool(String id) {
        boolean removed = pools.remove(id) != null;
        indices.remove(id);
        return removed;
    }

    public void addToPool(String poolId, MemoryDocument document) {
        PoolIndex idx = indices.get(poolId);
        if (idx == null || document == null) return;
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) return;
        try {
            float[] v = model.embed(document.title() + "\n" + document.content());
            idx.entries.put(document.conversationId(), new Entry(document, v));
        } catch (Exception e) {
            log.warn("SharedMemoryPool {}: Indizierung fehlgeschlagen: {}", poolId, e.getMessage());
        }
    }

    public List<MemoryDocument> searchPool(String poolId, String query, int topK) {
        PoolIndex idx = indices.get(poolId);
        if (idx == null || query == null || query.isBlank()) return List.of();
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null || idx.entries.isEmpty()) return List.of();
        int k = topK <= 0 ? 5 : topK;
        try {
            float[] qv = model.embed(query);
            List<Map.Entry<String, Entry>> scored = new ArrayList<>(idx.entries.entrySet());
            scored.sort(Comparator.comparingDouble((Map.Entry<String, Entry> e) ->
                    cosine(qv, e.getValue().vector)).reversed());
            List<MemoryDocument> res = new ArrayList<>();
            for (Map.Entry<String, Entry> e : scored) {
                if (res.size() >= k) break;
                res.add(e.getValue().document);
            }
            return res;
        } catch (Exception e) {
            log.warn("SharedMemoryPool {}: Suche fehlgeschlagen: {}", poolId, e.getMessage());
            return List.of();
        }
    }

    public int poolSize(String poolId) {
        PoolIndex idx = indices.get(poolId);
        return idx == null ? 0 : idx.entries.size();
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static class PoolIndex {
        final Map<String, Entry> entries = new ConcurrentHashMap<>();
    }

    private record Entry(MemoryDocument document, float[] vector) {
    }
}