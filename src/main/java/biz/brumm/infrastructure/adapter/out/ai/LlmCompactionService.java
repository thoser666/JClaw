package biz.brumm.infrastructure.adapter.out.ai;

import biz.brumm.config.CompactionProperties;
import biz.brumm.domain.model.CompactionResult;
import biz.brumm.domain.service.CompactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM-basierte Compaction: Erzeugt eine Zusammenfassung älterer Nachrichten
 * und ersetzt sie durch eine kompakte Zusammenfassung.
 */
@Service
public class LlmCompactionService implements CompactionService {

    private static final Logger log = LoggerFactory.getLogger(LlmCompactionService.class);

    private final ChatModel chatModel;
    private final CompactionProperties properties;

    public LlmCompactionService(ChatModel chatModel, CompactionProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    @Override
    public boolean isCompactionNeeded(List<Message> messages) {
        if (!properties.enabled()) {
            return false;
        }
        // Nur User- und Assistant-Nachrichten zählen (nicht SystemMessage)
        long nonSystemCount = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .count();
        return nonSystemCount > properties.threshold();
    }

    @Override
    public CompactionResult compact(List<Message> messages) {
        // SystemMessage immer behalten
        List<Message> systemMessages = messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .toList();
        List<Message> nonSystemMessages = messages.stream()
                .filter(m -> !(m instanceof SystemMessage))
                .toList();

        int retainCount = properties.retainCount();
        if (nonSystemMessages.size() <= retainCount) {
            log.debug("Compaction: Nicht genug Nachrichten zum Komprimieren ({}).", nonSystemMessages.size());
            return CompactionResult.of(
                    messages.stream().map(Message::getText).toList(),
                    "", 0, nonSystemMessages.size());
        }

        // Ältere Nachrichten komprimieren, jüngere beibehalten
        List<Message> toCompact = nonSystemMessages.subList(0, nonSystemMessages.size() - retainCount);
        List<Message> toRetain = nonSystemMessages.subList(nonSystemMessages.size() - retainCount, nonSystemMessages.size());

        // Zusammenfassung erzeugen
        String conversationText = toCompact.stream()
                .map(m -> m.getClass().getSimpleName() + ": " + m.getText())
                .collect(Collectors.joining("\n"));

        String summary = generateSummary(conversationText);

        // Neue Nachrichtenliste: SystemMessage + Summary + beibehaltene Nachrichten
        List<Message> compacted = new ArrayList<>(systemMessages);
        compacted.add(new UserMessage("[Zusammenfassung früherer Konversation]\n" + summary));
        compacted.addAll(toRetain);

        List<String> compactedTexts = compacted.stream().map(Message::getText).toList();

        log.info("Compaction durchgeführt: {} Nachrichten komprimiert, {} beibehalten, Summary={}.",
                toCompact.size(), toRetain.size(), summary.length() > 50 ? summary.substring(0, 50) + "..." : summary);

        return CompactionResult.of(compactedTexts, summary, toCompact.size(), toRetain.size());
    }

    private String generateSummary(String conversationText) {
        try {
            List<Message> summaryPrompt = List.of(
                    new SystemMessage(properties.summaryPrompt()),
                    new UserMessage(conversationText)
            );
            return chatModel.call(new Prompt(summaryPrompt))
                    .getResult()
                    .getOutput()
                    .getText();
        } catch (Exception e) {
            log.warn("Compaction-Zusammenfassung fehlgeschlagen: {}. Behalte alte Nachrichten.", e.getMessage());
            return "Zusammenfassung nicht verfügbar.";
        }
    }
}
