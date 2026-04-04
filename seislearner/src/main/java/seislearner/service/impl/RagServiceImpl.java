package seislearner.service.impl;

import seislearner.mapper.ChunkBgeM3Mapper;
import seislearner.model.entity.ChunkBgeM3;
import seislearner.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RagServiceImpl implements RagService {

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RestTemplate restTemplate;

    // 阿里云 DashScope API 配置
    @Value("${dashscope.api-key:sk-placeholder}")
    private String dashScopeApiKey;

    @Value("${dashscope.base-url:https://dashscope.aliyuncs.com}")
    private String dashScopeBaseUrl;

    // qwen3-vl-embedding 多模态向量模型（1536维，支持文本/图片/视频统一语义空间）
    private static final String EMBEDDING_MODEL = "qwen3-vl-embedding";
    private static final int EMBEDDING_DIMENSION = 1536;

    public RagServiceImpl(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.restTemplate = new RestTemplate();
    }

    @Data
    private static class DashScopeEmbeddingResponse {
        private String code;
        private String message;
        private Output output;

        @Data
        public static class Output {
            private EmbeddingItem[] embeddings;

            @Data
            public static class EmbeddingItem {
                private float[] embedding;
                private int text_index;
            }
        }
    }

    /**
     * 调用阿里云 DashScope MultiModalEmbedding API 获取向量
     * 使用 qwen3-vl-embedding 模型（1536维多模态）
     *
     * API 端点: POST /api/v1/services/embeddings/multimodal-embedding/multimodal-embedding
     * 输入格式: {"model":"qwen3-vl-embedding", "input":{"contents":[{"text":"..."}]}, "parameters":{"dimension":1536}}
     */
    private float[] doEmbed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding text cannot be blank");
        }

        String url = dashScopeBaseUrl + "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + dashScopeApiKey);

        // 多模态 embedding API 使用 contents 格式（不同于 text-embedding 的 texts 格式）
        Map<String, Object> body = Map.of(
            "model", EMBEDDING_MODEL,
            "input", Map.of(
                "contents", List.of(
                    Map.of("text", text)
                )
            ),
            "parameters", Map.of(
                "dimension", EMBEDDING_DIMENSION,
                "output_type", "dense"
            )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<DashScopeEmbeddingResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, DashScopeEmbeddingResponse.class);

            DashScopeEmbeddingResponse resp = response.getBody();
            Assert.notNull(resp, "DashScope embedding returned null response");
            Assert.notNull(resp.getOutput(),
                "Invalid embedding response from DashScope (no output): " + resp.getMessage());
            Assert.notEmpty(resp.getOutput().getEmbeddings(),
                "Invalid embedding response from DashScope (empty embeddings): " + resp.getMessage());

            log.debug("Embedding success: model={}, dimension={}", EMBEDDING_MODEL,
                resp.getOutput().getEmbeddings()[0].getEmbedding().length);

            return resp.getOutput().getEmbeddings()[0].getEmbedding();
        } catch (Exception e) {
            log.error("DashScope multimodal embedding call failed for text (first 50 chars): {}...",
                text.length() > 50 ? text.substring(0, 50) : text, e);
            throw new RuntimeException("Embedding API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        String queryEmbedding = toPgVector(doEmbed(title));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
