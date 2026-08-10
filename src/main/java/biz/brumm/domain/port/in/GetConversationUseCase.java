package biz.brumm.domain.port.in;

import biz.brumm.domain.model.ConversationMessage;

import java.util.List;

public interface GetConversationUseCase {
    List<ConversationMessage> getConversation(String contextId);
}
