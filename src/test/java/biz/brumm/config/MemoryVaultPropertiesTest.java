package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryVaultPropertiesTest {

    @Test
    void defaultDirIsApplied() {
        MemoryVaultProperties props = new MemoryVaultProperties(false, null);
        assertThat(props.enabled()).isFalse();
        assertThat(props.dir()).isEqualTo("./vault");
    }

    @Test
    void customValuesArePreserved() {
        MemoryVaultProperties props = new MemoryVaultProperties(true, "./data/vault");
        assertThat(props.enabled()).isTrue();
        assertThat(props.dir()).isEqualTo("./data/vault");
    }
}
