package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.service.BackupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST-API für Backup & Restore (P4-10).
 */
@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create() throws Exception {
        Path archive = backupService.createBackup();
        return ResponseEntity.ok(Map.of("archive", archive.toString(), "created", true));
    }

    @GetMapping
    public ResponseEntity<List<BackupService.BackupInfo>> list() throws Exception {
        return ResponseEntity.ok(backupService.listBackups());
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestParam("path") String path) {
        boolean ok = backupService.verifyBackup(Path.of(path));
        return ResponseEntity.ok(Map.of("path", path, "valid", ok));
    }

    @PostMapping("/restore")
    public ResponseEntity<Map<String, Object>> restore(
            @RequestParam("path") String path,
            @RequestParam(value = "target", required = false) String target) throws Exception {
        Path targetDir = target == null || target.isBlank()
                ? backupService.sourceDir()
                : Path.of(target);
        backupService.restoreBackup(Path.of(path), targetDir);
        return ResponseEntity.ok(Map.of("path", path, "restored", true, "target", targetDir.toString()));
    }
}