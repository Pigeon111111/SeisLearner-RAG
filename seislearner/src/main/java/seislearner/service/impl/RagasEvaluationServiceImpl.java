package seislearner.service.impl;

import seislearner.service.RagasEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.*;

/**
 * RAGAs评估服务实现
 * 通过REST API调用Python评估服务
 */
@Service
@Slf4j
public class RagasEvaluationServiceImpl implements RagasEvaluationService {
    
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${ragas.api.url:http://localhost:8001}")
    private String ragasApiUrl;
    
    public RagasEvaluationServiceImpl() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }
    
    public RagasEvaluationServiceImpl(String customApiUrl) {
        this();
        if (customApiUrl != null && !customApiUrl.isEmpty()) {
            this.ragasApiUrl = customApiUrl;
        }
    }
    
    @Override
    public EvaluationResult evaluate(EvaluationRequest request) {
        try {
            log.info("调用RAGAs评估服务，问题数? {}", request.getQuestions().size());
            
            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("questions", request.getQuestions());
            requestBody.put("ground_truths", request.getGroundTruths());
            requestBody.put("contexts", request.getContexts());
            requestBody.put("answers", request.getAnswers());
            
            if (request.getMetrics() != null && !request.getMetrics().isEmpty()) {
                requestBody.put("metrics", request.getMetrics());
            }

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            // 调用API
            String url = ragasApiUrl + "/evaluate";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                
                EvaluationResult result = new EvaluationResult();
                result.setStatus((String) responseBody.getOrDefault("status", "unknown"));
                result.setMessage((String) responseBody.getOrDefault("message", ""));
                result.setTimestamp((String) responseBody.getOrDefault("timestamp", ""));
                
                // 解析scores
                Map<String, Double> scores = new HashMap<>();
                Object scoresObj = responseBody.get("scores");
                if (scoresObj instanceof Map) {
                    Map<?, ?> scoresMap = (Map<?, ?>) scoresObj;
                    for (Map.Entry<?, ?> entry : scoresMap.entrySet()) {
                        if (entry.getKey() instanceof String && entry.getValue() instanceof Number) {
                            scores.put((String) entry.getKey(), ((Number) entry.getValue()).doubleValue());
                        }
                    }
                }
                result.setScores(scores);
                
                // 解析details
                Object detailsObj = responseBody.get("details");
                if (detailsObj instanceof Map) {
                    result.setDetails((Map<String, Object>) detailsObj);
                } else {
                    result.setDetails(new HashMap<>());
                }
                
                log.info("RAGAs评估完成，指标数? {}", scores.size());
                return result;
            } else {
                log.error("RAGAs评估服务返回异常状? {}", response.getStatusCode());
                return createErrorResult("评估服务返回异常状? " + response.getStatusCode());
            }
            
        } catch (HttpClientErrorException e) {
            log.error("RAGAs评估HTTP客户端错? {}", e.getMessage(), e);
            return createErrorResult("HTTP客户端错? " + e.getMessage());
        } catch (ResourceAccessException e) {
            log.error("无法连接到RAGAs评估服务: {}", e.getMessage(), e);
            return createErrorResult("无法连接到评估服务，请确保服务正在运? " + e.getMessage());
        } catch (Exception e) {
            log.error("RAGAs评估未知错误: {}", e.getMessage(), e);
            return createErrorResult("评估过程发生错误: " + e.getMessage());
        }
    }
    
    @Override
    public BatchEvaluationResult batchEvaluate(List<EvaluationRequest> requests, String batchId) {
        try {
            log.info("调用RAGAs批量评估服务，批次ID: {}, 请求数量: {}", batchId, requests.size());
            
            // 构建批量请求
            Map<String, Object> batchRequest = new HashMap<>();
            batchRequest.put("evaluations", requests);
            if (batchId != null && !batchId.isEmpty()) {
                batchRequest.put("batch_id", batchId);
            }

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(batchRequest, headers);
            
            // 调用批量评估API
            String url = ragasApiUrl + "/batch_evaluate";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                
                BatchEvaluationResult batchResult = new BatchEvaluationResult();
                batchResult.setStatus((String) responseBody.getOrDefault("status", "unknown"));
                batchResult.setMessage((String) responseBody.getOrDefault("message", ""));
                batchResult.setBatchId((String) responseBody.getOrDefault("batch_id", batchId != null ? batchId : ""));
                
                // 解析结果列表
                List<EvaluationResult> results = new ArrayList<>();
                Object resultsObj = responseBody.get("results");
                if (resultsObj instanceof List) {
                    List<?> resultsList = (List<?>) resultsObj;
                    for (Object resultObj : resultsList) {
                        if (resultObj instanceof Map) {
                            Map<String, Object> resultMap = (Map<String, Object>) resultObj;
                            EvaluationResult result = new EvaluationResult();
                            result.setStatus((String) resultMap.getOrDefault("status", "unknown"));
                            result.setMessage((String) resultMap.getOrDefault("message", ""));
                            result.setTimestamp((String) resultMap.getOrDefault("timestamp", ""));
                            
                            // 解析scores
                            Map<String, Double> scores = new HashMap<>();
                            Object scoresObj = resultMap.get("scores");
                            if (scoresObj instanceof Map) {
                                Map<?, ?> scoresMap = (Map<?, ?>) scoresObj;
                                for (Map.Entry<?, ?> entry : scoresMap.entrySet()) {
                                    if (entry.getKey() instanceof String && entry.getValue() instanceof Number) {
                                        scores.put((String) entry.getKey(), ((Number) entry.getValue()).doubleValue());
                                    }
                                }
                            }
                            result.setScores(scores);
                            
                            // 解析details
                            Object detailsObj = resultMap.get("details");
                            if (detailsObj instanceof Map) {
                                result.setDetails((Map<String, Object>) detailsObj);
                            } else {
                                result.setDetails(new HashMap<>());
                            }
                            
                            results.add(result);
                        }
                    }
                }
                batchResult.setResults(results);
                
                log.info("RAGAs批量评估完成，批次ID: {}, 结果数量: {}", batchId, results.size());
                return batchResult;
            } else {
                log.error("RAGAs批量评估服务返回异常状? {}", response.getStatusCode());
                return createBatchErrorResult("批量评估服务返回异常状? " + response.getStatusCode(), batchId);
            }
            
        } catch (Exception e) {
            log.error("RAGAs批量评估错误: {}", e.getMessage(), e);
            return createBatchErrorResult("批量评估过程发生错误: " + e.getMessage(), batchId);
        }
    }
    
    @Override
    public Map<String, String> getAvailableMetrics() {
        try {
            String url = ragasApiUrl + "/metrics";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, String> metrics = new HashMap<>();
                Map<String, Object> responseBody = response.getBody();
                
                for (Map.Entry<String, Object> entry : responseBody.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        metrics.put(entry.getKey(), (String) entry.getValue());
                    }
                }
                
                log.info("获取到RAGAs可用指标: {}", metrics.keySet());
                return metrics;
            }
        } catch (Exception e) {
            log.error("获取RAGAs可用指标失败: {}", e.getMessage(), e);
        }
        
        // 返回默认指标列表
        return getDefaultMetrics();
    }
    
    private EvaluationResult createErrorResult(String errorMessage) {
        EvaluationResult result = new EvaluationResult();
        result.setStatus("error");
        result.setMessage(errorMessage);
        result.setTimestamp(new Date().toString());
        result.setScores(new HashMap<>());
        result.setDetails(new HashMap<>());
        return result;
    }
    
    private BatchEvaluationResult createBatchErrorResult(String errorMessage, String batchId) {
        BatchEvaluationResult result = new BatchEvaluationResult();
        result.setStatus("error");
        result.setMessage(errorMessage);
        result.setBatchId(batchId != null ? batchId : "");
        result.setResults(new ArrayList<>());
        return result;
    }
    
    private Map<String, String> getDefaultMetrics() {
        Map<String, String> defaultMetrics = new HashMap<>();
        defaultMetrics.put("context_precision", "上下文精确率 - 检索到的上下文中与问题相关的比例");
        defaultMetrics.put("context_recall", "上下文召回率 - 检索到的上下文覆盖所有相关知识点的比例");
        defaultMetrics.put("faithfulness", "忠实度 - 答案是否忠实于提供的上下文，没有添加额外信息");
        defaultMetrics.put("answer_relevancy", "答案相关性 - 答案与问题的相关程度");
        defaultMetrics.put("answer_correctness", "答案正确性 - 答案与参考答案的匹配程度");
        defaultMetrics.put("answer_similarity", "答案语义相似性 - 答案与参考答案的语义相似度");
        defaultMetrics.put("context_relevancy", "上下文相关性 - 检索到的上下文与问题的相关程度");
        defaultMetrics.put("context_entity_recall", "上下文实体召回率 - 检索到的上下文中包含的实体比例");
        return defaultMetrics;
    }
}