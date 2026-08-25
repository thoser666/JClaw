package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.CronJob;
import biz.brumm.domain.port.out.CronJobStore;
import biz.brumm.domain.service.CronExpression;
import biz.brumm.domain.service.CronSchedulerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cron-jobs")
public class CronRestController {

    private final CronJobStore cronJobStore;
    private final CronSchedulerService cronSchedulerService;

    public CronRestController(CronJobStore cronJobStore, CronSchedulerService cronSchedulerService) {
        this.cronJobStore = cronJobStore;
        this.cronSchedulerService = cronSchedulerService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return cronJobStore.findAll().stream().map(this::toMap).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return cronJobStore.findById(id)
                .map(job -> ResponseEntity.ok(toMap(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String cronExpression = body.get("cronExpression");
        String prompt = body.get("prompt");
        String contextId = body.get("contextId");

        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name ist erforderlich."));
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cronExpression ist erforderlich."));
        }
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "prompt ist erforderlich."));
        }
        if (!CronExpression.isValid(cronExpression)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ungueltiger Cron-Ausdruck: " + cronExpression));
        }

        CronJob job = cronSchedulerService.createJob(name, cronExpression, prompt, contextId);
        return ResponseEntity.ok(toMap(job));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, String> body) {
        var existing = cronJobStore.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CronJob old = existing.get();
        String name = body.getOrDefault("name", old.name());
        String cronExpr = body.getOrDefault("cronExpression", old.cronExpression());
        String prompt = body.getOrDefault("prompt", old.prompt());
        String contextId = body.getOrDefault("contextId", old.contextId());

        if (!CronExpression.isValid(cronExpr)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ungueltiger Cron-Ausdruck: " + cronExpr));
        }

        CronJob updated = new CronJob(old.id(), name, cronExpr, prompt, contextId,
                old.enabled(), old.lastRunAt(), old.nextRunAt(), old.createdAt());
        updated = cronSchedulerService.recalculateNextRun(updated);
        cronJobStore.save(updated);
        return ResponseEntity.ok(toMap(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        return cronJobStore.findById(id).map(job -> {
            cronJobStore.deleteById(id);
            return ResponseEntity.ok(Map.<String, Object>of("deleted", true, "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String id) {
        return cronJobStore.findById(id).map(job -> {
            cronSchedulerService.executeJob(job);
            return ResponseEntity.ok(Map.<String, Object>of("executed", true, "id", id));
        }).orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toMap(CronJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.id());
        map.put("name", job.name());
        map.put("cronExpression", job.cronExpression());
        map.put("prompt", job.prompt());
        map.put("contextId", job.contextId());
        map.put("enabled", job.enabled());
        map.put("lastRunAt", job.lastRunAt() != null ? job.lastRunAt().toString() : null);
        map.put("nextRunAt", job.nextRunAt() != null ? job.nextRunAt().toString() : null);
        map.put("createdAt", job.createdAt() != null ? job.createdAt().toString() : null);
        return map;
    }
}
