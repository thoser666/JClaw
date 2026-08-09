package biz.brumm.domain.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopLimitExceededExceptionTest {

    @Test
    void exposesIterationCountInMessage() {
        AgentLoopLimitExceededException exception = new AgentLoopLimitExceededException(3);

        assertThat(exception.getMessage())
                .isEqualTo("Der Agent hat die maximale Anzahl von 3 Iteration(en) überschritten.");
    }
}
