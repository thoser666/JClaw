package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolInvocationTest {

    @Test
    void exposesAllFields() {
        ToolInvocation invocation = new ToolInvocation("calculate", "{\"expression\":\"2+2\"}", "4");

        assertThat(invocation.name()).isEqualTo("calculate");
        assertThat(invocation.arguments()).isEqualTo("{\"expression\":\"2+2\"}");
        assertThat(invocation.result()).isEqualTo("4");
    }
}
