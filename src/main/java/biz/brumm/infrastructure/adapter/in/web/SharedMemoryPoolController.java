package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.MemoryDocument;
import biz.brumm.domain.model.SharedMemoryPool;
import biz.brumm.domain.service.SharedMemoryPoolService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/memory/pools")
public class SharedMemoryPoolController {

    private final SharedMemoryPoolService service;

    public SharedMemoryPoolController(SharedMemoryPoolService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SharedMemoryPool> create(@RequestBody Map<String, String> body) {
        String id = body.getOrDefault("id", "");
        String name = body.get("name");
        String description = body.getOrDefault("description", "");
        return ResponseEntity.ok(service.createPool(id, name, description));
    }

    @GetMapping
    public ResponseEntity<List<SharedMemoryPool>> list() {
        return ResponseEntity.ok(service.listPools());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SharedMemoryPool> get(@PathVariable String id) {
        SharedMemoryPool p = service.getPool(id);
        if (p == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(p);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.deletePool(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<Void> add(@PathVariable String id, @RequestBody MemoryDocument document) {
        service.addToPool(id, document);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/search")
    public ResponseEntity<List<MemoryDocument>> search(@PathVariable String id,
                                                      @RequestParam("q") String query,
                                                      @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return ResponseEntity.ok(service.searchPool(id, query, limit));
    }
}