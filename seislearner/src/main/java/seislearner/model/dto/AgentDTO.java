package seislearner.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AgentDTO {
    private String id;

    private String name;

    private String description;

    private String systemPrompt;

    private ModelType model;

    private List<String> allowedTools;

    private List<String> allowedKbs;

    private ChatOptions chatOptions;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    @AllArgsConstructor
    public enum ModelType {
        DEEPSEEK_CHAT("deepseek-chat"),
        GLM_4_6("glm-4.6");

        @JsonValue
        private final String modelName;

        public static ModelType fromModelName(String modelName) {
            for (ModelType type : ModelType.values()) {
                if (type.modelName.equals(modelName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown model type: " + modelName);
        }
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class ChatOptions {
        private Double temperature;
        private Double topP;
        private Integer messageLength;
        private RetrievalOptions retrievalOptions;

        private static final Double DEFAULT_TEMPERATURE = 0.7;
        private static final Double DEFAULT_TOP_P = 1.0;
        private static final Integer DEFAULT_MESSAGE_LENGTH = 10;

        public static ChatOptions defaultOptions() {
            return ChatOptions.builder()
                    .temperature(DEFAULT_TEMPERATURE)
                    .topP(DEFAULT_TOP_P)
                    .messageLength(DEFAULT_MESSAGE_LENGTH)
                    .retrievalOptions(RetrievalOptions.defaultOptions())
                    .build();
        }
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class RetrievalOptions {
        // 是否启用混合检索
        private Boolean enableHybridSearch;
        // 是否启用递归检索
        private Boolean enableRecursiveSearch;
        // 是否启用Rerank精排
        private Boolean enableRerank;
        // 检索数量 topK
        private Integer topK;
        // 稠密向量权重 (0.0-1.0)
        private Double denseWeight;
        // 稀疏向量权重 (0.0-1.0)
        private Double sparseWeight;
        // 递归检索最大深度
        private Integer maxRecursionDepth;
        // 置信度阈值
        private Double confidenceThreshold;
        // 相似度阈值
        private Double similarityThreshold;

        private static final Boolean DEFAULT_ENABLE_HYBRID = true;
        private static final Boolean DEFAULT_ENABLE_RECURSIVE = true;
        private static final Boolean DEFAULT_ENABLE_RERANK = true;
        private static final Integer DEFAULT_TOP_K = 5;
        private static final Double DEFAULT_DENSE_WEIGHT = 0.7;
        private static final Double DEFAULT_SPARSE_WEIGHT = 0.3;
        private static final Integer DEFAULT_MAX_RECURSION_DEPTH = 4;
        private static final Double DEFAULT_CONFIDENCE_THRESHOLD = 0.5;
        private static final Double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

        public static RetrievalOptions defaultOptions() {
            return RetrievalOptions.builder()
                    .enableHybridSearch(DEFAULT_ENABLE_HYBRID)
                    .enableRecursiveSearch(DEFAULT_ENABLE_RECURSIVE)
                    .enableRerank(DEFAULT_ENABLE_RERANK)
                    .topK(DEFAULT_TOP_K)
                    .denseWeight(DEFAULT_DENSE_WEIGHT)
                    .sparseWeight(DEFAULT_SPARSE_WEIGHT)
                    .maxRecursionDepth(DEFAULT_MAX_RECURSION_DEPTH)
                    .confidenceThreshold(DEFAULT_CONFIDENCE_THRESHOLD)
                    .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                    .build();
        }
    }
}
