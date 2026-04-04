package seislearner.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import seislearner.agent.tools.Tool;
import seislearner.config.ChatClientRegistry;
import seislearner.converter.AgentConverter;
import seislearner.converter.ChatMessageConverter;
import seislearner.converter.KnowledgeBaseConverter;
import seislearner.mapper.AgentMapper;
import seislearner.mapper.ChatSessionMapper;
import seislearner.mapper.KnowledgeBaseMapper;
import seislearner.model.dto.AgentDTO;
import seislearner.model.dto.ChatMessageDTO;
import seislearner.model.dto.KnowledgeBaseDTO;
import seislearner.model.entity.Agent;
import seislearner.model.entity.ChatSession;
import seislearner.model.entity.KnowledgeBase;
import seislearner.service.ChatMessageFacadeService;
import seislearner.service.ConversationSummarizer;
import seislearner.service.SseService;
import seislearner.service.ToolFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SeisLearnerFactory {

    private static final Logger log = LoggerFactory.getLogger(SeisLearnerFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final ChatSessionMapper chatSessionMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final ConversationSummarizer conversationSummarizer;

    // 运行时Agent配置
    private AgentDTO agentConfig;

    public SeisLearnerFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            ChatSessionMapper chatSessionMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            ConversationSummarizer conversationSummarizer
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.chatSessionMapper = chatSessionMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.conversationSummarizer = conversationSummarizer;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复为List<Message>结构。
     * 采用两层结构：历史摘要 + 近期原始消息。
     * - 如果 chat_session.conversation_summary 存在，作为 SystemMessage 注入
     * - 只加载最近 messageLength 条原始消息，避免旧的长检索结果占满上下文
     */
    private List<Message> loadMemory(String chatSessionId) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        List<Message> memory = new ArrayList<>();

        // 第一层：注入历史对话摘要（如果有）
        ChatSession session = chatSessionMapper.selectById(chatSessionId);
        if (session != null && StringUtils.hasLength(session.getConversationSummary())) {
            String summaryText = "【历史对话摘要】\n" + session.getConversationSummary()
                    + "\n\n请基于以上历史上下文和后续的近期对话来回答用户的问题。";
            memory.add(new SystemMessage(summaryText));
            log.info("[loadMemory] 注入历史对话摘要, 长度={}", session.getConversationSummary().length());
        }

        // 第二层：加载近期原始消息
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    // 只在没有历史摘要时才添加原始 system 消息，避免重复
                    if (session == null || !StringUtils.hasLength(session.getConversationSummary())) {
                        memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    }
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata()
                                    .getToolCalls())
                            .build());
                    break;
                case TOOL:
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO
                                    .getMetadata()
                                    .getToolResponse()))
                            .build());
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return memory;
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        // 固定工具（系统强制）
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        // 可选工具（从Agent配置中读取）
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private SeisLearner buildAgentRuntime(
            Agent agent,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new SeisLearner(
                agent.getId(),
                agent.getModel(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                conversationSummarizer,
                chatSessionMapper
        );
    }

    /**
     * 创建一个SeisLearner实例
     */
    public SeisLearner create(String agentId, String chatSessionId) {
        Agent agent = loadAgent(agentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        List<Message> memory = loadMemory(chatSessionId);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        return buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId
        );
    }
}
