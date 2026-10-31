package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.service.MemoryRetrievalService;
import biz.brumm.domain.service.MemoryVaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST-API für den Open Memory Vault (P4-02): Konversation als Markdown
 * materialisieren und vorhandene Vault-Dokumente auflisten.
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryVaultController {

    private final MemoryVaultService memoryVaultService;
    private final MemoryRetrievalService retrievalService;

    public MemoryVaultController(MemoryVaultService memoryVaultService, MemoryRetrievalService retrievalService) {
        this.memoryVaultService = memoryVaultService;
        this.retrievalService = retrievalService;
    }

    @PostMapping("/{contextId}/sync")
    public ResponseEntity<Map<String, Object>> sync(@PathVariable String contextId) {
        boolean written = memoryVaultService.syncConversation(contextId);
        return written
                ? ResponseEntity.ok(Map.of("conversationId", contextId, "stored", true))
                : ResponseEntity.ok(Map.of("conversationId", contextId, "stored", false));
    }

    @GetMapping
    public ResponseEntity<List<MemoryDocument>> list() {
        return ResponseEntity.ok(memoryVaultService.listDocuments());
    }

    @GetMapping("/search")
    public ResponseEntity<List<MemoryDocument>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        if (retrievalService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(retrievalService.search(query, limit));
    }
}
