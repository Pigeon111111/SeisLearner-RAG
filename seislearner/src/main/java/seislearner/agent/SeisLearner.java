package seislearner.agent;

import seislearner.converter.ChatMessageConverter;
import seislearner.message.SseMessage;
import seislearner.model.dto.ChatMessageDTO;
import seislearner.model.dto.KnowledgeBaseDTO;
import seislearner.model.response.CreateChatMessageResponse;
import seislearner.model.vo.ChatMessageVO;
import seislearner.service.ChatMessageFacadeService;
import seislearner.service.ConversationSummarizer;
import seislearner.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import seislearner.mapper.ChatSessionMapper;
import seislearner.model.entity.ChatSession;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class SeisLearner {
    // Agent ID
    private String agentId;

    // 使用的模型名称（如 "deepseek-chat"）
    private String modelName;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具列表
    private List<ToolCallback> availableTools;

    // 可访问的知识库列表
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话ID
    private String chatSessionId;

    // 最大循环次数
    private static final Integer MAX_STEPS = 15;

    // 历史消息窗口大小（每条约6000字符 ≈ 3000 tokens，15条 ≈ 45000 tokens，远低于131072上限）
    private static final Integer DEFAULT_MAX_MESSAGES = 15;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    private ConversationSummarizer conversationSummarizer;

    private ChatSessionMapper chatSessionMapper;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要通过sse发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public SeisLearner() {
    }

    public SeisLearner(String agentId,
                     String modelName,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     ConversationSummarizer conversationSummarizer,
                     ChatSessionMapper chatSessionMapper
    ) {
        this.agentId = agentId;
        this.modelName = modelName;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.conversationSummarizer = conversationSummarizer;
        this.chatSessionMapper = chatSessionMapper;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要持久化的 Message 子类有以下两种:
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发起提问之间就已经持久化过
    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    // thinkPrompt 应该放到 system 中还有
    private boolean think() {
        String thinkPrompt = """
                现在你是一个智能的具身决策模型。
                请根据当前对话上下文，决定下一步的动作。
                \s
                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有强大的上下文时，优先从知识库中进行搜索
                """.formatted(this.availableKbs);

        // 将thinkPrompt 通过 .user(thinkPrompt) 的方式构建进 chatClient
        // 既能让每条messageList 的最后一条是 这条提示词，
        // 又能避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        // 保存
        saveMessage(output);
        refreshPendingMessages();

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return !toolCalls.isEmpty();
    }

    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果: {}", collect);

        // 保存工具调用
        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现Agent Loop
                int currentStep = i + 1;
                step();
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;

            // 对话结束后，总结本轮对话并更新到 chat_session
            summarizeAndPersist();
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        }
    }

    /**
     * 对话结束后，将本轮对话总结为摘要，持久化到 chat_session.conversation_summary。
     * 新摘要会与旧摘要合并，确保历史信息不丢失。
     */
    private void summarizeAndPersist() {
        try {
            List<Message> currentMessages = this.chatMemory.get(this.chatSessionId);
            if (currentMessages == null || currentMessages.size() < 2) {
                log.debug("[summarizeAndPersist] 消息数量不足，跳过总结");
                return;
            }

            // 获取当前使用的模型名称（从 agent 配置获取）
            String modelName = agentId; // agentId 在这里是 model 标识，需要在 Factory 中传入

            // 调用 ConversationSummarizer 生成本轮摘要
            String newSummary = conversationSummarizer.summarize(this.modelName, currentMessages);
            if (newSummary == null) {
                log.debug("[summarizeAndPersist] 本轮对话太短，无需总结");
                return;
            }

            // 读取旧的摘要
            ChatSession session = chatSessionMapper.selectById(this.chatSessionId);
            if (session == null) {
                log.warn("[summarizeAndPersist] 会话不存在: {}", this.chatSessionId);
                return;
            }

            String finalSummary;
            if (StringUtils.hasLength(session.getConversationSummary())) {
                // 与旧摘要合并
                finalSummary = conversationSummarizer.mergeSummaries(this.modelName,
                        List.of(session.getConversationSummary(), newSummary));
            } else {
                finalSummary = newSummary;
            }

            // 持久化到数据库
            chatSessionMapper.updateConversationSummary(this.chatSessionId, finalSummary);
            log.info("[summarizeAndPersist] 对话摘要已更新, sessionId={}, 长度={}",
                    this.chatSessionId, finalSummary.length());
        } catch (Exception e) {
            // 总结失败不应影响正常的对话流程
            log.warn("[summarizeAndPersist] 对话总结失败，不影响正常对话: {}", e.getMessage());
        }
    }


    @Override
    public String toString() {
        return "SeisLearner {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
