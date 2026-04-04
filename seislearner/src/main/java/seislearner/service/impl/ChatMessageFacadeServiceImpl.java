package seislearner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import seislearner.converter.ChatMessageConverter;
import seislearner.event.ChatEvent;
import seislearner.exception.BizException;
import seislearner.mapper.ChatMessageMapper;
import seislearner.model.dto.ChatMessageDTO;
import seislearner.model.entity.ChatMessage;
import seislearner.model.request.CreateChatMessageRequest;
import seislearner.model.request.UpdateChatMessageRequest;
import seislearner.model.response.CreateChatMessageResponse;
import seislearner.model.response.GetChatMessagesResponse;
import seislearner.model.vo.ChatMessageVO;
import seislearner.service.ChatMessageFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class ChatMessageFacadeServiceImpl implements ChatMessageFacadeService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;
    private final ApplicationEventPublisher publisher;

    @Override
    public GetChatMessagesResponse getChatMessagesBySessionId(String sessionId) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionId(sessionId);
        List<ChatMessageVO> result = new ArrayList<>();

        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageVO vo = chatMessageConverter.toVO(chatMessage);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return GetChatMessagesResponse.builder()
                .chatMessages(result.toArray(new ChatMessageVO[0]))
                .build();
    }

    @Override
    public List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit) {
        List<ChatMessage> chatMessages = chatMessageMapper.selectBySessionIdRecently(sessionId, limit);
        List<ChatMessageDTO> result = new ArrayList<>();
        for (ChatMessage chatMessage : chatMessages) {
            try {
                ChatMessageDTO dto = chatMessageConverter.toDTO(chatMessage);
                result.add(dto);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    @Override
    public CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request) {
        ChatMessage chatMessage = doCreateChatMessage(request);
        // 发布聊天通知事件
        publisher.publishEvent(new ChatEvent(
                        request.getAgentId(),
                        chatMessage.getSessionId(),
                        chatMessage.getContent()
                )
        );
        // 返回生成?chatMessageId
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO) {
        ChatMessage chatMessage = doCreateChatMessage(chatMessageDTO);
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    @Override
    public CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request) {
        ChatMessage chatMessage = doCreateChatMessage(request);
        // ?createChatMessage 的区别在于，Agent 创建?chatMessage 不需要发布事?
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessage.getId())
                .build();
    }

    private ChatMessage doCreateChatMessage(CreateChatMessageRequest request) {
        // ?CreateChatMessageRequest 转换?ChatMessageDTO
        ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(request);
        // ?ChatMessageDTO 转换?ChatMessage 实体
        return doCreateChatMessage(chatMessageDTO);
    }

    private ChatMessage doCreateChatMessage(ChatMessageDTO chatMessageDTO) {
        try {
            // ?ChatMessageDTO 转换?ChatMessage 实体
            ChatMessage chatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 设置创建时间和更新时?
            LocalDateTime now = LocalDateTime.now();
            chatMessage.setCreatedAt(now);
            chatMessage.setUpdatedAt(now);
            // 插入数据库，ID 由数据库自动生成
            int result = chatMessageMapper.insert(chatMessage);
            if (result <= 0) {
                throw new BizException("创建聊天消息失败");
            }
            return chatMessage;
        } catch (JsonProcessingException e) {
            throw new BizException("创建聊天消息时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent) {
        // 查询现有的聊天消?
        ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
        if (existingChatMessage == null) {
            throw new BizException("聊天消息不存? " + chatMessageId);
        }

        // 将追加内容添加到现有内容后面
        String currentContent = existingChatMessage.getContent() != null
                ? existingChatMessage.getContent()
                : "";
        String updatedContent = currentContent + appendContent;

        // 创建更新后的消息对象
        ChatMessage updatedChatMessage = ChatMessage.builder()
                .id(existingChatMessage.getId())
                .sessionId(existingChatMessage.getSessionId())
                .role(existingChatMessage.getRole())
                .content(updatedContent)
                .metadata(existingChatMessage.getMetadata())
                .createdAt(existingChatMessage.getCreatedAt())
                .updatedAt(LocalDateTime.now())
                .build();

        // 更新数据?
        int result = chatMessageMapper.updateById(updatedChatMessage);
        if (result <= 0) {
            throw new BizException("追加聊天消息内容失败");
        }

        // 返回聊天消息ID
        return CreateChatMessageResponse.builder()
                .chatMessageId(chatMessageId)
                .build();
    }

    @Override
    public void deleteChatMessage(String chatMessageId) {
        ChatMessage chatMessage = chatMessageMapper.selectById(chatMessageId);
        if (chatMessage == null) {
            throw new BizException("聊天消息不存? " + chatMessageId);
        }

        int result = chatMessageMapper.deleteById(chatMessageId);
        if (result <= 0) {
            throw new BizException("删除聊天消息失败");
        }
    }

    @Override
    public void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request) {
        try {
            // 查询现有的聊天消?
            ChatMessage existingChatMessage = chatMessageMapper.selectById(chatMessageId);
            if (existingChatMessage == null) {
                throw new BizException("聊天消息不存? " + chatMessageId);
            }

            // 将现?ChatMessage 转换?ChatMessageDTO
            ChatMessageDTO chatMessageDTO = chatMessageConverter.toDTO(existingChatMessage);

            // 使用 UpdateChatMessageRequest 更新 ChatMessageDTO
            chatMessageConverter.updateDTOFromRequest(chatMessageDTO, request);

            // 将更新后?ChatMessageDTO 转换?ChatMessage 实体
            ChatMessage updatedChatMessage = chatMessageConverter.toEntity(chatMessageDTO);

            // 保留原有?ID、sessionId、role 和创建时?
            updatedChatMessage.setId(existingChatMessage.getId());
            updatedChatMessage.setSessionId(existingChatMessage.getSessionId());
            updatedChatMessage.setRole(existingChatMessage.getRole());
            updatedChatMessage.setCreatedAt(existingChatMessage.getCreatedAt());
            updatedChatMessage.setUpdatedAt(LocalDateTime.now());

            // 更新数据?
            int result = chatMessageMapper.updateById(updatedChatMessage);
            if (result <= 0) {
                throw new BizException("更新聊天消息失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新聊天消息时发生序列化错误: " + e.getMessage());
        }
    }
}

