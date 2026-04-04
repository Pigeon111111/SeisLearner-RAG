package seislearner.event.listener;

import seislearner.agent.SeisLearner;
import seislearner.agent.SeisLearnerFactory;
import seislearner.event.ChatEvent;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ChatEventListener {

    private static final Logger log = LoggerFactory.getLogger(ChatEventListener.class);
    private final SeisLearnerFactory seislearnerFactory;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            // 创建一个Agent实例处理聊天事件
            SeisLearner agent = seislearnerFactory.create(event.getAgentId(), event.getSessionId());
            agent.run();
        } catch (Exception e) {
            // 异步处理异常不应影响消息已保存的结果
            log.error("[ChatEvent] Agent 执行失败, agentId={}, sessionId={}", event.getAgentId(), event.getSessionId(), e);
        }
    }
}
