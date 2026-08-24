package biz.brumm.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionResultTest {

    @Test
    void createsResult() {
        CompactionResult result = CompactionResult.of(
                List.of("summary", "msg3", "msg4"), "Zusammenfassung", 2, 2);

        assertThat(result.compactedMessages()).hasSize(3);
        assertThat(result.summary()).isEqualTo("Zusammenfassung");
        assertThat(result.messagesRemoved()).isEqualTo(2);
        assertThat(result.messagesRetained()).isEqualTo(2);
    }

    @Test
    void emptyCompaction() {
        CompactionResult result = CompactionResult.of(List.of("a"), "", 0, 1);

        assertThat(result.messagesRemoved()).isZero();
        assertThat(result.messagesRetained()).isEqualTo(1);
    }
}
