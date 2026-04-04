package seislearner.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionDTO {
    private String id;

    private String agentId;

    private String title;

    private MetaData metadata;

    // 历史对话摘要
    private String conversationSummary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
    }
}
