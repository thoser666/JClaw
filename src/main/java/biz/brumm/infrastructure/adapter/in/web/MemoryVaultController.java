package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.service.MemoryVaultService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public MemoryVaultController(MemoryVaultService memoryVaultService) {
        this.memoryVaultService = memoryVaultService;
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
}
