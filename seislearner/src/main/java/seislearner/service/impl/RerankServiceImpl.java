package seislearner.service.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import seislearner.service.RerankService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank 精排序服务实现
 * 使用阿里云 DashScope qwen3-vl-rerank 模型
 */
@Service
@Slf4j
public class RerankServiceImpl implements RerankService {

    private final RestTemplate restTemplate;

    @Value("${dashscope.api-key:sk-placeholder}")
    private String dashScopeApiKey;

    @Value("${dashscope.base-url:https://dashscope.aliyuncs.com}")
    private String dashScopeBaseUrl;

    private static final String RERANK_MODEL = "qwen3-vl-rerank";

    public RerankServiceImpl() {
        this.restTemplate = new RestTemplate();
    }

    @Data
    private static class DashScopeRerankResponse {
        private String code;
        private String message;
        private Output output;

        @Data
        public static class Output {
            private ResultItem[] results;

            @Data
            public static class ResultItem {
                private int index;
                @JsonProperty("relevance_score")
                private double relevanceScore;
            }
        }
    }

    /**
     * DashScope rerank API 使用嵌套格式：
     * { "model": "qwen3-vl-rerank",
     *   "input": { "query": "...", "documents": ["...", "..."] },
     *   "parameters": { "top_n": 5 } }
     */
    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topK) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }

        int actualTopK = Math.min(topK, documents.size());

        String url = dashScopeBaseUrl + "/api/v1/services/rerank/text-rerank/text-rerank";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + dashScopeApiKey);

        // qwen3-vl-rerank 使用嵌套结构
        Map<String, Object> body = Map.of(
            "model", RERANK_MODEL,
            "input", Map.of(
                "query", query,
                "documents", documents
            ),
            "parameters", Map.of(
                "top_n", actualTopK,
                "return_documents", true
            )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<DashScopeRerankResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, DashScopeRerankResponse.class);

            DashScopeRerankResponse resp = response.getBody();
            if (resp == null || resp.getOutput() == null || resp.getOutput().getResults() == null) {
                log.warn("Invalid rerank response, falling back to original order");
                return createFallbackResults(documents, actualTopK);
            }

            // 按返回的顺序构建结果（API 已排序）
            List<RerankResult> results = new ArrayList<>();
            for (DashScopeRerankResponse.Output.ResultItem item : resp.getOutput().getResults()) {
                int idx = item.getIndex();
                if (idx >= 0 && idx < documents.size()) {
                    results.add(new SimpleRerankResult(
                        documents.get(idx),
                        item.getRelevanceScore(),
                        idx
                    ));
                }
            }

            log.debug("Rerank completed: input_size={}, top_k={}, returned={}", 
                documents.size(), actualTopK, results.size());

            return results;
        } catch (Exception e) {
            log.error("DashScope rerank call failed for query (first 50 chars): {}...",
                query.length() > 50 ? query.substring(0, 50) : query, e);
            // 降级：返回原始顺序
            return createFallbackResults(documents, actualTopK);
        }
    }

    /**
     * 降级方案：当 rerank API 调用失败时，按原始顺序返回
     */
    private List<RerankResult> createFallbackResults(List<String> documents, int topK) {
        List<RerankResult> results = new ArrayList<>();
        int limit = Math.min(topK, documents.size());
        for (int i = 0; i < limit; i++) {
            results.add(new SimpleRerankResult(documents.get(i), 1.0 - (double) i / documents.size(), i));
        }
        return results;
    }

    /**
     * 简单的 RerankResult 实现
     */
    @Data
    private static class SimpleRerankResult implements RerankResult {
        private final String content;
        private final double score;
        private final int originalIndex;

        public SimpleRerankResult(String content, double score, int originalIndex) {
            this.content = content;
            this.score = score;
            this.originalIndex = originalIndex;
        }

        @Override
        public String getContent() { return content; }

        @Override
        public double getScore() { return score; }

        @Override
        public int getOriginalIndex() { return originalIndex; }
    }
}
