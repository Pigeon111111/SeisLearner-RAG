package seislearner.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * RAGAs评估服务接口
 * 用于通过REST API调用Python评估服务
 */
public interface RagasEvaluationService {
    
    /**
     * 单个评估请求
     * @param request 评估请求
     * @return 评估结果
     */
    EvaluationResult evaluate(EvaluationRequest request);
    
    /**
     * 批量评估请求
     * @param requests 批量评估请求列表
     * @param batchId 批次ID（可选）
     * @return 批量评估结果
     */
    BatchEvaluationResult batchEvaluate(List<EvaluationRequest> requests, String batchId);
    
    /**
     * 获取可用的评估指标
     * @return 指标名称到描述的映射
     */
    Map<String, String> getAvailableMetrics();
    
    /**
     * 评估请求模型
     */
    class EvaluationRequest {
        private List<String> questions;
        private List<String> groundTruths;
        private List<List<String>> contexts;
        private List<String> answers;
        private List<String> metrics;
        
        // 构造函数、getters和setters
        public EvaluationRequest() {}
        
        public EvaluationRequest(List<String> questions, List<String> groundTruths, 
                                List<List<String>> contexts, List<String> answers, 
                                List<String> metrics) {
            this.questions = questions;
            this.groundTruths = groundTruths;
            this.contexts = contexts;
            this.answers = answers;
            this.metrics = metrics;
        }
        
        public List<String> getQuestions() { return questions; }
        public void setQuestions(List<String> questions) { this.questions = questions; }
        
        public List<String> getGroundTruths() { return groundTruths; }
        public void setGroundTruths(List<String> groundTruths) { this.groundTruths = groundTruths; }
        
        public List<List<String>> getContexts() { return contexts; }
        public void setContexts(List<List<String>> contexts) { this.contexts = contexts; }
        
        public List<String> getAnswers() { return answers; }
        public void setAnswers(List<String> answers) { this.answers = answers; }
        
        public List<String> getMetrics() { return metrics; }
        public void setMetrics(List<String> metrics) { this.metrics = metrics; }
    }
    
    /**
     * 评估结果模型
     */
    class EvaluationResult {
        private String status;
        private String message;
        private Map<String, Double> scores;
        private Map<String, Object> details;
        private String timestamp;
        
        public EvaluationResult() {}
        
        public EvaluationResult(String status, String message, Map<String, Double> scores, 
                               Map<String, Object> details, String timestamp) {
            this.status = status;
            this.message = message;
            this.scores = scores;
            this.details = details;
            this.timestamp = timestamp;
        }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public Map<String, Double> getScores() { return scores; }
        public void setScores(Map<String, Double> scores) { this.scores = scores; }
        
        public Map<String, Object> getDetails() { return details; }
        public void setDetails(Map<String, Object> details) { this.details = details; }
        
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }
    
    /**
     * 批量评估结果模型
     */
    class BatchEvaluationResult {
        private String status;
        private String message;
        private String batchId;
        private List<EvaluationResult> results;
        
        public BatchEvaluationResult() {}
        
        public BatchEvaluationResult(String status, String message, String batchId, 
                                    List<EvaluationResult> results) {
            this.status = status;
            this.message = message;
            this.batchId = batchId;
            this.results = results;
        }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        
        public List<EvaluationResult> getResults() { return results; }
        public void setResults(List<EvaluationResult> results) { this.results = results; }
    }
}
