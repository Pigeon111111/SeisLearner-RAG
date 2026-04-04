package seislearner.agent.examples;

import seislearner.agent.AgentState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * V1 版本：基础聊天功能
 * 目标：集成LLM，能正常聊天
 *
 * 核心功能：
 * - 使用 ChatClient 与 LLM 交互
 * - 使用 ChatMemory 管理对话历史
 * - 支持系统提示词
 * - 简单的用户输入 -> AI 回复流程
 */
@Slf4j
public class SeisLearnerV1 {
    // 名称
    protected String name;

    // 描述
    protected String description;

    // 系统提示词
    protected String systemPrompt;

    // ChatClient 实例
    protected ChatClient chatClient;

    // 聊天记录
    protected ChatMemory chatMemory;

    // 状态
    protected AgentState agentState;

    // 对话 ID（用于内部管理）
    protected String sessionId;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    public SeisLearnerV1() {}

    public SeisLearnerV1(String name,
                      String description,
                      String systemPrompt,
                      ChatClient chatClient,
                      Integer maxMessages,
                      String sessionId) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.chatClient = chatClient;
        this.sessionId = sessionId != null ? sessionId : "default-session";
        this.agentState = AgentState.IDLE;

        // 初始化聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages != null ? maxMessages : DEFAULT_MAX_MESSAGES)
                .build();

        // 添加系统提示词
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(this.sessionId, new SystemMessage(systemPrompt));
        }
    }

    /**
     * 处理用户输入并返回AI回复
     */
    public String chat(String userInput) {
        Assert.notNull(userInput, "用户输入不能为空");

        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent 状态不是 IDLE，当前状态：" + agentState);
        }

        try {
            agentState = AgentState.THINKING;

            // 添加用户消息到记录
            UserMessage userMessage = new UserMessage(userInput);
            chatMemory.add(sessionId, userMessage);

            // 构建提示词
            Prompt prompt = Prompt.builder()
                    .messages(chatMemory.get(sessionId))
                    .build();

            // 调用 LLM
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

            Assert.notNull(response, "ChatResponse 不能为空");

            AssistantMessage assistantMessage = response.getResult().getOutput();
            String aiResponse = assistantMessage.getText();

            // 将 AI 回答添加到记录
            chatMemory.add(sessionId, assistantMessage);

            agentState = AgentState.FINISHED;

            return aiResponse;

        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("聊天过程中发生错误", e);
            throw new RuntimeException("聊天过程中发生错误", e);
        } finally {
            // 重置状态以便下次使用
            agentState = AgentState.IDLE;
        }
    }

    /**
     * 获取当前对话历史
     */
    public List<Message> getConversationHistory() {
        return chatMemory.get(sessionId);
    }

    /**
     * 重置对话历史
     */
    public void reset() {
        chatMemory.clear(sessionId);
        if (StringUtils.hasLength(systemPrompt)) {
            chatMemory.add(sessionId, new SystemMessage(systemPrompt));
        }
        agentState = AgentState.IDLE;
    }

    @Override
    public String toString() {
        return "SeisLearnerV1 {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
