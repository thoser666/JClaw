package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.in.GetConversationUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationRestController {

    private final GetConversationUseCase getConversationUseCase;

    public ConversationRestController(GetConversationUseCase getConversationUseCase) {
        this.getConversationUseCase = getConversationUseCase;
    }

    @GetMapping("/{contextId}")
    public ResponseEntity<List<ConversationMessage>> getConversation(@PathVariable String contextId) {
        return ResponseEntity.ok(getConversationUseCase.getConversation(contextId));
    }
}
