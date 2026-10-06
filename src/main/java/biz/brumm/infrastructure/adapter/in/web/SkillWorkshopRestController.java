package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.SkillProposal;
import biz.brumm.domain.model.SkillWorkshopConfig;
import biz.brumm.domain.service.SkillWorkshopService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST-Spiegel des OpenClaw-Skill-Workshops ({@code openclaw skills workshop propose-create|propose-update|
 * list|inspect|revise|apply|reject|quarantine}, P4-06). Die REST-Aufrufe sind die Operator-Kanal-Freigabe:
 * bei {@code approvalPolicy: "pending"} brauchen agent-initiierte Lifecycle-Aktionen diese Freigabe
 * (im Request per {@code agentInitiated: true} markiert → 403).
 */
@RestController
@RequestMapping("/api/v1/skills/workshop")
public class SkillWorkshopRestController {

    private final SkillWorkshopService skillWorkshopService;

    public SkillWorkshopRestController(SkillWorkshopService skillWorkshopService) {
        this.skillWorkshopService = skillWorkshopService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(toConfigMap());
    }

    @PostMapping("/proposals")
    public ResponseEntity<Map<String, Object>> proposeCreate(@RequestBody Map<String, Object> body) {
        SkillProposal proposal = skillWorkshopService.proposeCreate(
                requireString(body, "name"), requireString(body, "description"), requireString(body, "content"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(proposal));
    }

    @PostMapping("/proposals/update")
    public ResponseEntity<Map<String, Object>> proposeUpdate(@RequestBody Map<String, Object> body) {
        SkillProposal proposal = skillWorkshopService.proposeUpdate(
                requireString(body, "name"), requireString(body, "description"), requireString(body, "content"));
        return ResponseEntity.status(HttpStatus.CREATED).body(toMap(proposal));
    }

    @GetMapping("/proposals")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(skillWorkshopService.listProposals().stream().map(this::toMap).toList());
    }

    @GetMapping("/proposals/{proposalId}")
    public ResponseEntity<Map<String, Object>> inspect(@PathVariable String proposalId) {
        return ResponseEntity.ok(toMap(skillWorkshopService.inspect(proposalId)));
    }

    @PostMapping("/proposals/{proposalId}/revise")
    public ResponseEntity<Map<String, Object>> revise(@PathVariable String proposalId,
                                                      @RequestBody Map<String, Object> body) {
        SkillProposal proposal = skillWorkshopService.revise(proposalId,
                requireString(body, "description"), requireString(body, "content"));
        return ResponseEntity.ok(toMap(proposal));
    }

    @PostMapping("/proposals/{proposalId}/apply")
    public ResponseEntity<Map<String, Object>> apply(@PathVariable String proposalId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(skillWorkshopService.apply(proposalId, agentInitiated(body))));
    }

    @PostMapping("/proposals/{proposalId}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String proposalId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(skillWorkshopService.reject(proposalId, reason(body), agentInitiated(body))));
    }

    @PostMapping("/proposals/{proposalId}/quarantine")
    public ResponseEntity<Map<String, Object>> quarantine(@PathVariable String proposalId,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(toMap(skillWorkshopService.quarantine(proposalId, reason(body), agentInitiated(body))));
    }

    private Map<String, Object> toConfigMap() {
        SkillWorkshopConfig config = skillWorkshopService.config();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("approvalPolicy", config.approvalPolicy().name());
        map.put("maxPending", config.maxPending());
        map.put("maxSkillBytes", config.maxSkillBytes());
        map.put("autonomousEnabled", config.autonomousEnabled());
        return map;
    }

    private Map<String, Object> toMap(SkillProposal proposal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("proposalId", proposal.proposalId());
        map.put("type", proposal.type().name());
        map.put("name", proposal.name());
        map.put("description", proposal.description());
        map.put("content", proposal.content());
        map.put("status", proposal.status().name());
        map.put("targetHash", proposal.targetHash());
        map.put("reason", proposal.reason());
        map.put("createdAt", proposal.createdAt() != null ? proposal.createdAt().toString() : null);
        map.put("updatedAt", proposal.updatedAt() != null ? proposal.updatedAt().toString() : null);
        return map;
    }

    private static String requireString(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " ist erforderlich.");
        }
        return text;
    }

    private static String reason(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object value = body.get("reason");
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static boolean agentInitiated(Map<String, Object> body) {
        if (body == null) {
            return false;
        }
        return Boolean.TRUE.equals(body.get("agentInitiated"));
    }
}