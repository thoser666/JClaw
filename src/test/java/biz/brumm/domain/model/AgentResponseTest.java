package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentResponseTest {

    @Test
    void copiesToolInvocationsDefensively() {
        List<ToolInvocation> invocations = new ArrayList<>();
        invocations.add(new ToolInvocation("calculate", "{}", "4"));

        AgentResponse response = new AgentResponse("Antwort", Instant.now(), invocations, 1, null);
        invocations.add(new ToolInvocation("other", "{}", "x"));

        assertThat(response.toolInvocations()).hasSize(1);
        assertThat(response.toolInvocations()).isNotSameAs(invocations);
        assertThatThrownBy(() -> response.toolInvocations().add(new ToolInvocation("y", "{}", "z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void ofFactoryReturnsEmptyInvocationsAndSingleIteration() {
        AgentResponse response = AgentResponse.of("Antwort");

        assertThat(response.content()).isEqualTo("Antwort");
        assertThat(response.toolInvocations()).isEmpty();
        assertThat(response.iterations()).isEqualTo(1);
        assertThat(response.timestamp()).isNotNull();
    }
}
