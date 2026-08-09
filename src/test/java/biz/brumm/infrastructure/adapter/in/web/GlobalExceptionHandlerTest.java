package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.domain.service.AgentLoopLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handlesIllegalArgumentAsBadRequest() {
        ResponseEntity<Map<String, String>> response =
                handler.handleIllegalArgument(new IllegalArgumentException("Ungültige Eingabe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Ungültige Eingabe"));
    }

    @Test
    void handlesAgentLoopLimitAsServerError() {
        ResponseEntity<Map<String, String>> response =
                handler.handleAgentLoopLimit(new AgentLoopLimitExceededException(5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(Map.of("error",
                "Der Agent hat die maximale Anzahl von 5 Iteration(en) überschritten."));
    }
}
