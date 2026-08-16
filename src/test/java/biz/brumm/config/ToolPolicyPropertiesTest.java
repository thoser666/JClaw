package biz.brumm.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolPolicyPropertiesTest {

    @Test
    void normalizesNullListsToEmpty() {
        ToolPolicyProperties properties = new ToolPolicyProperties(null, null);
        assertThat(properties.allow()).isEmpty();
        assertThat(properties.deny()).isEmpty();
    }

    @Test
    void trimsEntriesAndDropsBlanks() {
        ToolPolicyProperties properties = new ToolPolicyProperties(List.of(" readFile ", " ", "glob"), List.of("runCommand"));
        assertThat(properties.allow()).containsExactly("readFile", "glob");
        assertThat(properties.deny()).containsExactly("runCommand");
    }

    @Test
    void keepsAllowsAndDenies() {
        ToolPolicyProperties properties = new ToolPolicyProperties(List.of("readFile", "glob"), List.of("writeFile"));
        assertThat(properties.allow()).containsExactly("readFile", "glob");
        assertThat(properties.deny()).containsExactly("writeFile");
    }
}
