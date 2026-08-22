package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ApiKey;
import biz.brumm.domain.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST-API für API-Token-Verwaltung.
 * Bietet Endpunkte zum Erstellen, Auflisten und Löschen von API-Token.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthRestController {

    private final AuthService authService;

    public AuthRestController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Listet alle gespeicherten API-Token auf (ohne die tatsächlichen Token-Werte).
     */
    @GetMapping("/tokens")
    public ResponseEntity<List<Map<String, Object>>> listTokens() {
        List<Map<String, Object>> tokens = authService.listApiKeys().stream()
                .map(key -> Map.<String, Object>of(
                        "id", key.id(),
                        "name", key.name(),
                        "createdAt", key.createdAt().toString()))
                .toList();
        return ResponseEntity.ok(tokens);
    }

    /**
     * Erstellt einen neuen API-Token. Der rohe Token wird nur einmalig in der Antwort ausgegeben.
     */
    @PostMapping("/tokens")
    public ResponseEntity<Map<String, String>> createToken(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name ist erforderlich."));
        }

        String rawToken = authService.createApiKey(name);

        return ResponseEntity.ok(Map.of(
                "token", rawToken,
                "name", name,
                "message", "Token wurde erstellt. Speichere ihn sicher — er wird nicht erneut angezeigt."
        ));
    }

    /**
     * Löscht einen API-Token anhand seiner ID.
     */
    @DeleteMapping("/tokens/{id}")
    public ResponseEntity<Map<String, String>> deleteToken(@PathVariable String id) {
        authService.deleteApiKey(id);
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Token gelöscht."));
    }
}
