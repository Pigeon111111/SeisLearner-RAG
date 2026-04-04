package seislearner.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseDTO {
    private String id;

    private String name;

    private String description;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
        private String version;
    }

    @Override
    public String toString() {
        return "{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
