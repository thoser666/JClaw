package biz.brumm.infrastructure.adapter.in.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API-Versions- und Stabilitätsinformationen (P4-16 Stable API).
 */
@RestController
@RequestMapping("/api")
@ApiVersion("v1")
public class VersionController {

    @GetMapping("/version")
    public Map<String, Object> version() {
        return Map.of(
                "current", "v1",
                "supported", List.of("v1"),
                "deprecated", List.of(),
                "deprecationPolicy", "Sunset-Header + Deprecation-Header, min. 6 Monate Vorlauf"
        );
    }
}