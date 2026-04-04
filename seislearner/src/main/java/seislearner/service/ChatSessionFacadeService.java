package seislearner.service;

import seislearner.model.request.CreateChatSessionRequest;
import seislearner.model.request.UpdateChatSessionRequest;
import seislearner.model.response.CreateChatSessionResponse;
import seislearner.model.response.GetChatSessionResponse;
import seislearner.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {
    GetChatSessionsResponse getChatSessions();

    GetChatSessionResponse getChatSession(String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String chatSessionId);

    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}
