package seislearner.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import seislearner.config.ChatClientRegistry;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 对话摘要服务。
 * 在一轮 Agent 对话完成后，将整轮对话（含工具调用结果）总结为精简的摘要，
 * 替代原始消息存入上下文窗口，避免旧的长检索结果占满上下文。
 */
@Service
@Slf4j
public class ConversationSummarizer {

    private final ChatClientRegistry chatClientRegistry;

    // 摘要的最大字符长度
    private static final int MAX_SUMMARY_LENGTH = 800;

    public ConversationSummarizer(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * 将一轮对话总结为精简摘要。
     *
     * @param modelName  模型名称（如 "deepseek-chat"）
     * @param messages   这一轮产生的所有消息（按时间顺序）
     * @return 总结后的摘要文本；如果消息太少或总结失败则返回 null
     */
    public String summarize(String modelName, List<Message> messages) {
        // 至少需要一轮问答（user + assistant）才值得总结
        if (messages == null || messages.size() < 2) {
            return null;
        }

        // 构建对话文本，只保留 user/assistant 的核心内容
        StringBuilder dialogText = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof UserMessage userMsg) {
                String content = userMsg.getText();
                if (content != null && !content.isBlank()) {
                    // 截断过长的用户消息（避免 prompt 太长）
                    if (content.length() > 500) {
                        content = content.substring(0, 500) + "...";
                    }
                    dialogText.append("用户: ").append(content).append("\n");
                }
            } else if (msg instanceof AssistantMessage assistantMsg) {
                String content = assistantMsg.getText();
                if (content != null && !content.isBlank()) {
                    // assistant 消息也可能很长，截断
                    if (content.length() > 1000) {
                        content = content.substring(0, 1000) + "...";
                    }
                    dialogText.append("助手: ").append(content).append("\n");
                }
                // ToolResponseMessage 和带有 toolCalls 的 AssistantMessage 不在摘要中展开
                // 因为 LLM 已经基于它们给出了回答，摘要只需保留问答核心
            }
        }

        String dialog = dialogText.toString().trim();
        if (dialog.isEmpty()) {
            return null;
        }

        // 如果对话本身已经很短，不需要总结
        if (dialog.length() < 200) {
            return null;
        }

        return doSummarize(modelName, dialog);
    }

    private String doSummarize(String modelName, String dialogText) {
        try {
            ChatClient chatClient = chatClientRegistry.get(modelName);
            if (chatClient == null) {
                log.warn("[ConversationSummarizer] 未找到模型: {}, 跳过总结", modelName);
                return null;
            }

            String summaryPrompt = """
                    请将以下对话总结为一段简洁的摘要，要求：
                    1. 保留用户的核心问题和意图
                    2. 保留助手回答中的关键结论、数据和知识点
                    3. 如果助手引用了知识库来源，保留来源文件名
                    4. 省略检索过程的细节（如具体检索了多少条结果、递归了几轮等）
                    5. 使用第三人称，格式如「用户询问了...，助手回答了...」
                    6. 严格控制长度在%d字以内
                    
                    对话内容：
                    %s
                    
                    摘要：
                    """.formatted(MAX_SUMMARY_LENGTH, dialogText);

            Prompt prompt = new Prompt(List.of(new UserMessage(summaryPrompt)));
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();

            String summary = response.getResult().getOutput().getText();
            if (summary != null && !summary.isBlank()) {
                // 截断过长的摘要
                if (summary.length() > MAX_SUMMARY_LENGTH) {
                    summary = summary.substring(0, MAX_SUMMARY_LENGTH) + "...";
                }
                log.info("[ConversationSummarizer] 对话总结完成, 原始长度={}, 摘要长度={}", dialogText.length(), summary.length());
                return summary;
            }
        } catch (Exception e) {
            log.warn("[ConversationSummarizer] 总结失败, 将保留原始消息: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将多个历史摘要合并为一个整体摘要。
     * 当历史摘要累积过多时使用。
     */
    public String mergeSummaries(String modelName, List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        if (summaries.size() == 1) {
            return summaries.get(0);
        }

        String combined = summaries.stream()
                .map(s -> "- " + s)
                .collect(Collectors.joining("\n"));

        try {
            ChatClient chatClient = chatClientRegistry.get(modelName);
            if (chatClient == null) return null;

            String mergePrompt = """
                    以下是之前对话的多段摘要，请合并为一段连贯的摘要（%d字以内），
                    保留所有关键信息，去除重复内容：
                    
                    %s
                    
                    合并后的摘要：
                    """.formatted(MAX_SUMMARY_LENGTH, combined);

            Prompt prompt = new Prompt(List.of(new UserMessage(mergePrompt)));
            ChatResponse response = chatClient.prompt(prompt).call().chatResponse();
            String merged = response.getResult().getOutput().getText();

            if (merged != null && !merged.isBlank()) {
                if (merged.length() > MAX_SUMMARY_LENGTH) {
                    merged = merged.substring(0, MAX_SUMMARY_LENGTH) + "...";
                }
                log.info("[ConversationSummarizer] 摘要合并完成, {}段 -> 1段", summaries.size());
                return merged;
            }
        } catch (Exception e) {
            log.warn("[ConversationSummarizer] 摘要合并失败: {}", e.getMessage());
        }
        // 合并失败，直接拼接
        return String.join("；", summaries);
    }
}
