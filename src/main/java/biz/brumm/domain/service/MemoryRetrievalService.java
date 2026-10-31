package biz.brumm.domain.service;

import biz.brumm.domain.model.MemoryDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantisches Retrieval über den Open Memory Vault (P4-02): bettet Vault-Dokumente
 * und Suchanfragen über ein {@link EmbeddingModel} ein und liefert die ähnlichsten
 * Dokumente via Kosinus-Ähnlichkeit (Top-K).
 *
 * <p>Das Embedding-Modell ist per {@code jclaw.memory.embedding.enabled=true}
 * (Deny-by-Default) aktiviert. Ist kein Modell verfügbar (Feature aus oder kein
 * Ollama-Endpoint), bleiben {@link #index(MemoryDocument)} und {@link #search(String, int)}
 * No-ops bzw. liefern eine leere Liste — der Vault bleibt ohne Retrieval nutzbar.</p>
 */
@Service
public class MemoryRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrievalService.class);

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    // In-Memory-Vektorindex: conversationId -> (Dokument, Embedding)
    private final Map<String, Entry> index = new ConcurrentHashMap<>();

    public MemoryRetrievalService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * Bettet ein Vault-Dokument ein und speichert es im Vektorindex.
     * No-op, wenn kein Embedding-Modell verfügbar ist.
     */
    public void index(MemoryDocument document) {
        if (document == null || document.conversationId() == null || document.conversationId().isBlank()) {
            return;
        }
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            return;
        }
        try {
            String text = document.title() + "\n" + document.content();
            float[] vector = model.embed(text);
            index.put(document.conversationId(), new Entry(document, vector));
        } catch (Exception e) {
            log.warn("Memory-Retrieval: Einbetten von {} fehlgeschlagen: {}", document.conversationId(), e.getMessage());
        }
    }

    /**
     * Entfernt ein Dokument aus dem Vektorindex.
     */
    public void remove(String conversationId) {
        if (conversationId != null) {
            index.remove(conversationId);
        }
    }

    /**
     * Semantische Top-K-Suche über die indizierten Vault-Dokumente.
     * Liefert eine leere Liste, wenn kein Embedding-Modell verfügbar ist oder der
     * Index leer ist.
     */
    public List<MemoryDocument> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null || index.isEmpty()) {
            return List.of();
        }
        int k = topK <= 0 ? 5 : topK;
        try {
            float[] queryVector = model.embed(query);
            List<Map.Entry<String, Entry>> scored = new ArrayList<>(index.entrySet());
            scored.sort(Comparator.comparingDouble((Map.Entry<String, Entry> e) ->
                    cosineSimilarity(queryVector, e.getValue().vector)).reversed());
            List<MemoryDocument> result = new ArrayList<>();
            for (Map.Entry<String, Entry> e : scored) {
                if (result.size() >= k) break;
                result.add(e.getValue().document);
            }
            return result;
        } catch (Exception e) {
            log.warn("Memory-Retrieval: Suche fehlgeschlagen: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * @return Anzahl der indizierten Dokumente (für Tests/Diagnose)
     */
    public int size() {
        return index.size();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Entry(MemoryDocument document, float[] vector) {
    }
}