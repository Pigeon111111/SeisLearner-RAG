package seislearner.agent.examples;

import seislearner.agent.tools.test.CityTool;
import seislearner.agent.tools.test.DateTool;
import seislearner.agent.tools.test.WeatherTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

/**
 * SeisLearnerV2 测试类
 * 测试工具调用功能（ReAct 模型）
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.openai.api-key=test-key",
    "spring.ai.openai.chat.options.model=gpt-3.5-turbo"
})
public class SeisLearnerV2Test {

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

        // 使用深度模拟，使得prompt().system().toolCallbacks().call() 链式调用能够工作
        org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec mockRequestSpec = org.mockito.Mockito.mock(
            org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS
        );
        when(mockRequestSpec.call()).thenReturn(mockCallResponseSpec);

        when(chatClient.prompt(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(mockRequestSpec);
    }

    @Autowired
    private CityTool cityTool;

    @Autowired
    private DateTool dateTool;

    @Autowired
    private WeatherTool weatherTool;

    @Test
    public void testToolCalling() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        SeisLearnerV2 agent = new SeisLearnerV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息",
                chatClient,
                20,
                "test-session-v2",
                Arrays.asList(toolCallbacks)
        );

        // 测试需要调用工具的对话
        String userInput = "今天的天气怎么样？";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertNotNull(response);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testMultipleToolCalls() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        SeisLearnerV2 agent = new SeisLearnerV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息",
                chatClient,
                20,
                "test-session-v2-multi",
                Arrays.asList(toolCallbacks)
        );

        // 测试需要调用多个工具的对话
        String userInput = "请告诉我今天的日期和当前城市，然后查询这个城市今天的天气";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testConversationWithoutToolCalling() {
        // 创建 V2 实例（不提供工具）
        SeisLearnerV2 agent = new SeisLearnerV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个友好的助手",
                chatClient,
                20,
                "test-session-v2-no-tool",
                List.of() // 不提供工具
        );

        // 测试不需要工具的普通对话
        String userInput = "你好，请介绍一下你自己";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
    }

    @Test
    public void testReActLoop() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        SeisLearnerV2 agent = new SeisLearnerV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息",
                chatClient,
                20,
                "test-session-v2-react",
                Arrays.asList(toolCallbacks)
        );

        // 测试 ReAct 循环（think-execute）
        String userInput = "我想知道今天的天气，请先告诉我今天的日期";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }
}
