package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.service.AgentLoopLimitExceededException;
import biz.brumm.domain.service.GoalOperationException;
import biz.brumm.domain.service.SkillWorkshopException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(AgentLoopLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleAgentLoopLimit(AgentLoopLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(GoalOperationException.class)
    public ResponseEntity<Map<String, String>> handleGoalOperation(GoalOperationException ex) {
        HttpStatus status = ex.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(SkillWorkshopException.class)
    public ResponseEntity<Map<String, String>> handleSkillWorkshop(SkillWorkshopException ex) {
        HttpStatus status = switch (ex.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case APPROVAL_REQUIRED -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }
}
