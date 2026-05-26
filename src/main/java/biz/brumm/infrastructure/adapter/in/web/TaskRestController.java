package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.AgentCommand;
import biz.brumm.domain.model.AgentResponse;
import biz.brumm.domain.port.in.ExecuteTaskUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskRestController {

    private final ExecuteTaskUseCase executeTaskUseCase;

    public TaskRestController(ExecuteTaskUseCase executeTaskUseCase) {
        this.executeTaskUseCase = executeTaskUseCase;
    }

    @PostMapping
    public ResponseEntity<AgentResponse> triggerTask(@RequestBody TaskRequestDto request) {
        AgentCommand command = new AgentCommand(request.prompt(), request.contextId());
        AgentResponse response = executeTaskUseCase.handle(command);
        return ResponseEntity.ok(response);
    }

    public record TaskRequestDto(String prompt, String contextId) {}
}