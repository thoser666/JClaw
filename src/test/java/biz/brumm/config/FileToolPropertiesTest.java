package biz.brumm.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolPropertiesTest {

    @Test
    void acceptsValidValues() {
        assertThatCode(() -> new FileToolProperties("./workspace", 2048)).doesNotThrowAnyException();
    }

    @Test
    void acceptsMissingWorkdirWithoutValidationError() {
        assertThatCode(() -> new FileToolProperties(null, null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankWorkdir() {
        assertThatThrownBy(() -> new FileToolProperties(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workdir");
    }

    @Test
    void rejectsNonPositiveMaxReadBytes() {
        assertThatThrownBy(() -> new FileToolProperties("./workspace", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-read-bytes");
        assertThatThrownBy(() -> new FileToolProperties("./workspace", -5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void effectiveMaxReadBytesFallsBackToDefault() {
        assertThat(new FileToolProperties("./workspace", null).effectiveMaxReadBytes())
                .isEqualTo(1_048_576L);
        assertThat(new FileToolProperties("./workspace", 4096).effectiveMaxReadBytes()).isEqualTo(4096L);
    }
}
