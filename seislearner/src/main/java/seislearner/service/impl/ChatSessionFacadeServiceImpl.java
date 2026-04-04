package seislearner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import seislearner.converter.ChatSessionConverter;
import seislearner.exception.BizException;
import seislearner.mapper.ChatSessionMapper;
import seislearner.model.dto.ChatSessionDTO;
import seislearner.model.entity.ChatSession;
import seislearner.model.request.CreateChatSessionRequest;
import seislearner.model.request.UpdateChatSessionRequest;
import seislearner.model.response.CreateChatSessionResponse;
import seislearner.model.response.GetChatSessionResponse;
import seislearner.model.response.GetChatSessionsResponse;
import seislearner.model.vo.ChatSessionVO;
import seislearner.service.ChatSessionFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatSessionFacadeServiceImpl implements ChatSessionFacadeService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionConverter chatSessionConverter;

    @Override
    public GetChatSessionsResponse getChatSessions() {
        List<ChatSession> chatSessions = chatSessionMapper.selectAll();
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            try {
                ChatSessionVO vo = chatSessionConverter.toVO(chatSession);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public GetChatSessionResponse getChatSession(String chatSessionId) {
        ChatSession chatSession = chatSessionMapper.selectById(chatSessionId);
        if (chatSession != null) {
            try {
                ChatSessionVO vo = chatSessionConverter.toVO(chatSession);
                return GetChatSessionResponse.builder()
                        .chatSession(vo)
                        .build();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        throw new BizException("聊天会话不存? " + chatSessionId);
    }

    @Override
    public GetChatSessionsResponse getChatSessionsByAgentId(String agentId) {
        List<ChatSession> chatSessions = chatSessionMapper.selectByAgentId(agentId);
        List<ChatSessionVO> result = new ArrayList<>();
        for (ChatSession chatSession : chatSessions) {
            try {
                ChatSessionVO vo = chatSessionConverter.toVO(chatSession);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetChatSessionsResponse.builder()
                .chatSessions(result.toArray(new ChatSessionVO[0]))
                .build();
    }

    @Override
    public CreateChatSessionResponse createChatSession(CreateChatSessionRequest request) {
        try {
            // ?CreateChatSessionRequest 转换?ChatSessionDTO
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(request);
            
            // ?ChatSessionDTO 转换?ChatSession 实体
            ChatSession chatSession = chatSessionConverter.toEntity(chatSessionDTO);
            
            // 设置创建时间和更新时?
            LocalDateTime now = LocalDateTime.now();
            chatSession.setCreatedAt(now);
            chatSession.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = chatSessionMapper.insert(chatSession);
            if (result <= 0) {
                throw new BizException("创建聊天会话失败");
            }
            
            // 返回生成?chatSessionId
            return CreateChatSessionResponse.builder()
                    .chatSessionId(chatSession.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天会话时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteChatSession(String chatSessionId) {
        ChatSession chatSession = chatSessionMapper.selectById(chatSessionId);
        if (chatSession == null) {
            throw new BizException("聊天会话不存? " + chatSessionId);
        }
        
        int result = chatSessionMapper.deleteById(chatSessionId);
        if (result <= 0) {
            throw new BizException("删除聊天会话失败");
        }
    }

    @Override
    public void updateChatSession(String chatSessionId, UpdateChatSessionRequest request) {
        try {
            // 查询现有的聊天会?
            ChatSession existingChatSession = chatSessionMapper.selectById(chatSessionId);
            if (existingChatSession == null) {
                throw new BizException("聊天会话不存? " + chatSessionId);
            }
            
            // 将现?ChatSession 转换?ChatSessionDTO
            ChatSessionDTO chatSessionDTO = chatSessionConverter.toDTO(existingChatSession);
            
            // 使用 UpdateChatSessionRequest 更新 ChatSessionDTO
            chatSessionConverter.updateDTOFromRequest(chatSessionDTO, request);
            
            // 将更新后?ChatSessionDTO 转换?ChatSession 实体
            ChatSession updatedChatSession = chatSessionConverter.toEntity(chatSessionDTO);
            
            // 保留原有?ID、agentId 和创建时?
            updatedChatSession.setId(existingChatSession.getId());
            updatedChatSession.setAgentId(existingChatSession.getAgentId());
            updatedChatSession.setCreatedAt(existingChatSession.getCreatedAt());
            updatedChatSession.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据?
            int result = chatSessionMapper.updateById(updatedChatSession);
            if (result <= 0) {
                throw new BizException("更新聊天会话失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天会话时发生序列化错误: " + e.getMessage());
        }
    }
}
