package seislearner.message;

import seislearner.model.vo.ChatMessageVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class SseMessage {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        private String chatMessageId;
    }

    // 自定义消息类?
    // 1. AI 生成
    // 2. AI 规划?
    // 3. AI 思考中
    // 4. AI 执行?
    // 5. AI 完成
    public enum Type {
        AI_GENERATED_CONTENT,
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_DONE,
    }
}
