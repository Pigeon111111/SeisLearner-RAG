package seislearner.controller;

import seislearner.model.common.ApiResponse;
import seislearner.model.request.CreateChatSessionRequest;
import seislearner.model.request.UpdateChatSessionRequest;
import seislearner.model.response.CreateChatSessionResponse;
import seislearner.model.response.GetChatSessionResponse;
import seislearner.model.response.GetChatSessionsResponse;
import seislearner.service.ChatSessionFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ChatSessionController {

    private final ChatSessionFacadeService chatSessionFacadeService;

    // 查询所有聊天会?
    @GetMapping("/chat-sessions")
    public ApiResponse<GetChatSessionsResponse> getChatSessions() {
        return ApiResponse.success(chatSessionFacadeService.getChatSessions());
    }

    // 查询单个聊天会话
    @GetMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<GetChatSessionResponse> getChatSession(@PathVariable String chatSessionId) {
        return ApiResponse.success(chatSessionFacadeService.getChatSession(chatSessionId));
    }

    // 根据 agentId 查询聊天会话
    @GetMapping("/chat-sessions/agent/{agentId}")
    public ApiResponse<GetChatSessionsResponse> getChatSessionsByAgentId(@PathVariable String agentId) {
        return ApiResponse.success(chatSessionFacadeService.getChatSessionsByAgentId(agentId));
    }

    // 创建聊天会话
    @PostMapping("/chat-sessions")
    public ApiResponse<CreateChatSessionResponse> createChatSession(@RequestBody CreateChatSessionRequest request) {
        return ApiResponse.success(chatSessionFacadeService.createChatSession(request));
    }

    // 删除聊天会话
    @DeleteMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> deleteChatSession(@PathVariable String chatSessionId) {
        chatSessionFacadeService.deleteChatSession(chatSessionId);
        return ApiResponse.success();
    }

    // 更新聊天会话
    @PatchMapping("/chat-sessions/{chatSessionId}")
    public ApiResponse<Void> updateChatSession(@PathVariable String chatSessionId, @RequestBody UpdateChatSessionRequest request) {
        chatSessionFacadeService.updateChatSession(chatSessionId, request);
        return ApiResponse.success();
    }
}
