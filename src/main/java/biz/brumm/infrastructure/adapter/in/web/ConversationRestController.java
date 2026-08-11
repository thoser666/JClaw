package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.model.ConversationMessage;
import biz.brumm.domain.port.in.DeleteConversationUseCase;
import biz.brumm.domain.port.in.GetConversationUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationRestController {

    private final GetConversationUseCase getConversationUseCase;
    private final DeleteConversationUseCase deleteConversationUseCase;

    public ConversationRestController(GetConversationUseCase getConversationUseCase,
                                      DeleteConversationUseCase deleteConversationUseCase) {
        this.getConversationUseCase = getConversationUseCase;
        this.deleteConversationUseCase = deleteConversationUseCase;
    }

    @GetMapping("/{contextId}")
    public ResponseEntity<List<ConversationMessage>> getConversation(@PathVariable String contextId) {
        return ResponseEntity.ok(getConversationUseCase.getConversation(contextId));
    }

    @DeleteMapping("/{contextId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String contextId) {
        deleteConversationUseCase.deleteConversation(contextId);
        return ResponseEntity.noContent().build();
    }
}
