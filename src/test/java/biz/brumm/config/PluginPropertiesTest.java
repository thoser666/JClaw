package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginPropertiesTest {

    @Test
    void appliesDefaultDirForMissingValue() {
        assertThat(new PluginProperties(null).dir()).isEqualTo("./plugins");
    }

    @Test
    void blankDirIsReplacedWithDefault() {
        assertThat(new PluginProperties("   ").dir()).isEqualTo("./plugins");
    }

    @Test
    void keepsConfiguredDir() {
        assertThat(new PluginProperties("/tmp/plugins").dir()).isEqualTo("/tmp/plugins");
    }
}
