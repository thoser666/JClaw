package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateTimeToolTest {

    private final DateTimeTool tool = new DateTimeTool();

    @Test
    void returnsIsoDateTimeWithTimezone() {
        assertThat(tool.getCurrentDateTime("Europe/Berlin"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:\\d{2}|Z)");
    }

    @Test
    void returnsUtcOffsetForUtcTimezone() {
        assertThat(tool.getCurrentDateTime("UTC")).endsWith("Z");
    }

    @Test
    void matchesActualOffsetOfGivenTimezone() {
        String expectedOffset = ZonedDateTime.now(ZoneId.of("Europe/Berlin")).getOffset().toString();
        assertThat(tool.getCurrentDateTime("Europe/Berlin")).endsWith(expectedOffset);
    }

    @Test
    void usesSystemTimezoneWhenEmpty() {
        assertThat(tool.getCurrentDateTime(null)).isNotBlank();
        assertThat(tool.getCurrentDateTime(" ")).isNotBlank();
    }

    @Test
    void rejectsInvalidTimezone() {
        assertThatThrownBy(() -> tool.getCurrentDateTime("Not/AZone"))
                .isInstanceOf(DateTimeException.class);
    }
}
