package seislearner.service;

import seislearner.model.dto.ChatMessageDTO;
import seislearner.model.request.CreateChatMessageRequest;
import seislearner.model.request.UpdateChatMessageRequest;
import seislearner.model.response.CreateChatMessageResponse;
import seislearner.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
