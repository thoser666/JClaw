package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.config.ToolPolicyProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultToolPolicyTest {

    private static DefaultToolPolicy policy(ToolPolicyProperties properties) {
        return new DefaultToolPolicy(properties);
    }

    @Test
    void enablesEverythingWhenNoListsConfigured() {
        DefaultToolPolicy policy = policy(new ToolPolicyProperties(null, null));
        assertThat(policy.isToolEnabled("readFile")).isTrue();
        assertThat(policy.isToolEnabled("runCommand")).isTrue();
        assertThat(policy.isToolEnabled("math-server_add")).isTrue();
    }

    @Test
    void allowlistOnlyEnablesConfiguredTools() {
        DefaultToolPolicy policy = policy(new ToolPolicyProperties(List.of("readFile", "glob"), null));
        assertThat(policy.isToolEnabled("readFile")).isTrue();
        assertThat(policy.isToolEnabled("glob")).isTrue();
        assertThat(policy.isToolEnabled("runCommand")).isFalse();
        assertThat(policy.isToolEnabled("writeFile")).isFalse();
    }

    @Test
    void denylistOnlyDisablesConfiguredTools() {
        DefaultToolPolicy policy = policy(new ToolPolicyProperties(null, List.of("runCommand")));
        assertThat(policy.isToolEnabled("runCommand")).isFalse();
        assertThat(policy.isToolEnabled("readFile")).isTrue();
        assertThat(policy.isToolEnabled("math-server_add")).isTrue();
    }

    @Test
    void denyWinsOverAllow() {
        DefaultToolPolicy policy = policy(new ToolPolicyProperties(List.of("runCommand", "readFile"), List.of("runCommand")));
        assertThat(policy.isToolEnabled("runCommand")).isFalse();
        assertThat(policy.isToolEnabled("readFile")).isTrue();
    }

    @Test
    void rejectsBlankNames() {
        DefaultToolPolicy policy = policy(new ToolPolicyProperties(null, null));
        assertThat(policy.isToolEnabled(null)).isFalse();
        assertThat(policy.isToolEnabled("  ")).isFalse();
    }
}
