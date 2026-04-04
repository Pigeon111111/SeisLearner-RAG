package seislearner.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ChatMessageDTO {
    private String id;

    private String sessionId;

    private RoleType role;

    private String content;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class MetaData {
        private ToolResponseMessage.ToolResponse toolResponse;
        private List<AssistantMessage.ToolCall> toolCalls;
    }

    @Getter
    @AllArgsConstructor
    public enum RoleType {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system"),
        TOOL("tool");

        @JsonValue
        private final String role;

        public static RoleType fromRole(String role) {
            for (RoleType value : values()) {
                if (value.role.equals(role)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Invalid role: " + role);
        }
    }
}
