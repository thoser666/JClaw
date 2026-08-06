package biz.brumm.infrastructure.adapter.out.ai.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DateTimeToolTest {

    private final DateTimeTool tool = new DateTimeTool();

    @Test
    void returnsIsoDateTimeWithTimezone() {
        assertThat(tool.getCurrentDateTime("Europe/Berlin"))
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:\\d{2}|Z)");
    }

    @Test
    void usesSystemTimezoneWhenEmpty() {
        assertThat(tool.getCurrentDateTime(null)).isNotBlank();
        assertThat(tool.getCurrentDateTime(" ")).isNotBlank();
    }
}
