package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HookResultTest {

    @Test
    void proceedWithoutOutput() {
        HookResult result = HookResult.proceed("test-hook");
        assertThat(result.allowed()).isTrue();
        assertThat(result.output()).isNull();
        assertThat(result.hookName()).isEqualTo("test-hook");
    }

    @Test
    void proceedWithOutput() {
        HookResult result = HookResult.proceed("test-hook", "modified prompt");
        assertThat(result.allowed()).isTrue();
        assertThat(result.output()).isEqualTo("modified prompt");
    }

    @Test
    void blockWithReason() {
        HookResult result = HookResult.block("test-hook", "not allowed");
        assertThat(result.allowed()).isFalse();
        assertThat(result.output()).isEqualTo("not allowed");
        assertThat(result.hookName()).isEqualTo("test-hook");
    }
}
