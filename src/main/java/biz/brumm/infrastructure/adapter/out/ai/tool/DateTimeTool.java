package biz.brumm.infrastructure.adapter.out.ai.tool;

import biz.brumm.domain.port.out.AgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTimeTool implements AgentTool {

    @Tool(name = "getCurrentDateTime",
            description = "Liefert das aktuelle Datum und die aktuelle Uhrzeit inklusive Zeitzonen-Offset.")
    public String getCurrentDateTime(
            @ToolParam(description = "Java-Zeitzonen-ID, z. B. 'Europe/Berlin'. Optional, Standard ist die System-Zeitzone.")
            String timezone) {
        ZoneId zone = (timezone == null || timezone.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(timezone);
        return ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
