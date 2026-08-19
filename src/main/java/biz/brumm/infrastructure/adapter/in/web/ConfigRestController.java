package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.config.json5.Json5ConfigReloadService;
import biz.brumm.config.json5.Json5ConfigValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * REST-API für Konfigurations-Reload. Bietet {@code POST /api/v1/config.apply}
 * zum manuellen Neuladen der JSON5-Konfiguration ({@code openclaw.json}).
 *
 * <p>Laufende Agents behalten ihre aktuelle Konfiguration — nur neue
 * Agent-Aufrufe verwenden die aktualisierten Werte.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ConfigRestController {

    private static final Logger log = LoggerFactory.getLogger(ConfigRestController.class);

    private final Json5ConfigReloadService reloadService;

    public ConfigRestController(Json5ConfigReloadService reloadService) {
        this.reloadService = reloadService;
    }

    /**
     * Lädt die JSON5-Konfiguration ({@code openclaw.json}) neu.
     *
     * @return Erfolgsmeldung oder Fehlerdetails
     */
    @PostMapping("/config.apply")
    public ResponseEntity<Map<String, Object>> configApply() {
        log.info("Manueller Config-Reload angefordert.");

        try {
            boolean reloaded = reloadService.reload();
            if (reloaded) {
                return ResponseEntity.ok(Map.of(
                        "status", "ok",
                        "message", "Konfiguration erfolgreich neu geladen."
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "status", "skipped",
                        "message", "Keine Konfiguration vorhanden — Reload übersprungen."
                ));
            }
        } catch (Json5ConfigValidationException e) {
            log.error("Config-Reload fehlgeschlagen (Validierung): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Validierungsfehler: " + e.getMessage()
            ));
        } catch (IOException e) {
            log.error("Config-Reload fehlgeschlagen (I/O): {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "I/O-Fehler: " + e.getMessage()
            ));
        }
    }
}
