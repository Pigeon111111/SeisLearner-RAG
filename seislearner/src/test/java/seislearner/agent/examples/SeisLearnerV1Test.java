package seislearner.agent.examples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SeisLearnerV1 测试类
 * 测试基础聊天功能
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.openai.api-key=test-key",
    "spring.ai.openai.chat.options.model=gpt-3.5-turbo"
})
public class SeisLearnerV1Test {

    @MockBean
    @Qualifier("deepseek-chat")
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        // 模拟 ChatClient 的调用链
        org.springframework.ai.chat.model.ChatResponse mockChatResponse = org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation mockGeneration = org.mockito.Mockito.mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage mockAssistantMessage = org.mockito.Mockito.mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        when(mockAssistantMessage.getText()).thenReturn("这是一条模拟回复");
        when(mockGeneration.getOutput()).thenReturn(mockAssistantMessage);
        when(mockChatResponse.getResult()).thenReturn(mockGeneration);

        org.springframework.ai.chat.client.ChatClient.CallResponseSpec mockCallResponseSpec = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec.class);
        when(mockCallResponseSpec.chatResponse()).thenReturn(mockChatResponse);

        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec mockRequestSpec = org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class);
        when(mockRequestSpec.call()).thenReturn(mockCallResponseSpec);

        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(mockRequestSpec);
    }

    @Test
    public void testBasicChat() {
        // 创建 V1 实例
        SeisLearnerV1 agent = new SeisLearnerV1(
                "test-agent-v1",
                "测试 Agent V1",
                "你是一个友好的助手",
                chatClient,
                20,
                "test-session-v1"
        );

        // 测试简单对话
        String userInput = "你好，请介绍一下你自己";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testMultiTurnConversation() {
        // 创建 V1 实例
        SeisLearnerV1 agent = new SeisLearnerV1(
                "test-agent-v1",
                "测试 Agent V1",
                "",
                chatClient,
                20,
                "test-session-v1-multi"
        );

        // 第一轮对话
        String response1 = agent.chat("我的名字叫做张三");
        assertNotNull(response1);
        System.out.println("第一轮 - [用户]: 我的名字叫做张三");
        System.out.println("第一轮 - [AI]: " + response1);

        // 第二轮对话（测试上下文记忆）
        String response2 = agent.chat("我的名字叫做什么？");
        assertNotNull(response2);
        System.out.println("第二轮 - [用户]: 我的名字叫做什么？");
        System.out.println("第二轮 - [AI]: " + response2);

        // 验证对话历史包含多轮对话
        assertTrue(agent.getConversationHistory().size() >= 4); // 至少包含：系统消息 + 用户消息1 + AI回复1 + 用户消息2 + AI回复2
    }

    @Test
    public void testResetConversation() {
        // 创建 V1 实例
        SeisLearnerV1 agent = new SeisLearnerV1(
                "test-agent-v1",
                "测试 Agent V1",
                "你是一个助手",
                chatClient,
                20,
                "test-session-v1-reset"
        );

        // 进行对话
        agent.chat("你好");
        int historySizeBeforeReset = agent.getConversationHistory().size();

        // 重置对话
        agent.reset();

        // 验证对话历史已重置（只保留系统消息）
        int historySizeAfterReset = agent.getConversationHistory().size();
        assertTrue(historySizeAfterReset < historySizeBeforeReset);
        assertTrue(historySizeAfterReset <= 1); // 只有系统消息

        System.out.println("重置前历史长度: " + historySizeBeforeReset);
        System.out.println("重置后历史长度: " + historySizeAfterReset);
    }
}
