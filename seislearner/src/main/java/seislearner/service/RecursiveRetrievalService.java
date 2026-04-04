package seislearner.service;

import java.util.List;
import java.util.Map;

/**
 * 递归检索机制服务接口
 * 实现自适应深度检索，支持Step-Back、HyDE、问题分解策略
 */
public interface RecursiveRetrievalService {
    
    /**
     * 递归检索：根据查询复杂度和置信度决定检索深度
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param initialLimit 初始检索结果数量
     * @return 检索到的文本内容列表
     */
    List<String> recursiveSearch(String kbId, String query, int initialLimit);
    
    /**
     * 递归检索，返回详细结果
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param initialLimit 初始检索结果数量
     * @return 递归检索结果，包含检索深度和策略信息
     */
    RecursiveSearchResult recursiveSearchDetailed(String kbId, String query, int initialLimit);

    /**
     * 递归检索，返回带来源信息的详细结果
     * 与 recursiveSearchDetailed 的区别：每个 context 附带 docId, chunkId, score
     * 并且有字符预算控制，防止上下文溢出
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param initialLimit 初始检索结果数量
     * @param maxTotalChars 输出总字符预算（0 表示不限制）
     * @return 带来源信息的检索结果
     */
    RecursiveSearchResultWithSources recursiveSearchDetailedWithSources(
            String kbId, String query, int initialLimit, int maxTotalChars);
    
    /**
     * Step-Back策略：生成更高层次的问题进行检索
     *
     * @param originalQuery 原始查询
     * @return 高层次问题
     */
    String stepBackQuery(String originalQuery);
    
    /**
     * HyDE策略：生成假设答案，用于改进检索
     *
     * @param query 查询文本
     * @return 假设答案
     */
    String generateHypotheticalAnswer(String query);
    
    /**
     * 问题分解策略：将复杂问题分解为多个子问题
     *
     * @param complexQuery 复杂查询
     * @return 子问题列表
     */
    List<String> decomposeQuery(String complexQuery);
    
    /**
     * 评估查询复杂度
     *
     * @param query 查询文本
     * @return 复杂度分数（0-1）
     */
    double assessQueryComplexity(String query);
    
    /**
     * 评估检索结果置信度
     *
     * @param retrievedContexts 检索到的上下文
     * @param query 查询文本
     * @return 置信度分数（0-1）
     */
    double assessConfidence(List<String> retrievedContexts, String query);
    
    /**
     * 递归检索结果数据类
     */
    class RecursiveSearchResult {
        private List<String> contexts;
        private int retrievalDepth;
        private RetrievalStrategy primaryStrategy;
        private List<RetrievalStep> steps;
        private double finalConfidence;
        private String explanation;
        
        public RecursiveSearchResult(List<String> contexts, int retrievalDepth, 
                                    RetrievalStrategy primaryStrategy, List<RetrievalStep> steps,
                                    double finalConfidence, String explanation) {
            this.contexts = contexts;
            this.retrievalDepth = retrievalDepth;
            this.primaryStrategy = primaryStrategy;
            this.steps = steps;
            this.finalConfidence = finalConfidence;
            this.explanation = explanation;
        }
        
        // Getters and setters
        public List<String> getContexts() { return contexts; }
        public void setContexts(List<String> contexts) { this.contexts = contexts; }
        
        public int getRetrievalDepth() { return retrievalDepth; }
        public void setRetrievalDepth(int retrievalDepth) { this.retrievalDepth = retrievalDepth; }
        
        public RetrievalStrategy getPrimaryStrategy() { return primaryStrategy; }
        public void setPrimaryStrategy(RetrievalStrategy primaryStrategy) { this.primaryStrategy = primaryStrategy; }
        
        public List<RetrievalStep> getSteps() { return steps; }
        public void setSteps(List<RetrievalStep> steps) { this.steps = steps; }
        
        public double getFinalConfidence() { return finalConfidence; }
        public void setFinalConfidence(double finalConfidence) { this.finalConfidence = finalConfidence; }
        
        public String getExplanation() { return explanation; }
        public void setExplanation(String explanation) { this.explanation = explanation; }
    }

    /**
     * 带来源信息的检索结果条目
     */
    class SourcedContext {
        private final String content;
        private final String docId;
        private final String filename;    // 原始文件名（如 "地震勘探原理.pdf"）
        private final String chunkId;
        private final int startOffset;    // 在原文档中的起始偏移（字符数），0 表示未知
        private final int endOffset;      // 在原文档中的结束偏移，0 表示未知
        private final double score;

        public SourcedContext(String content, String docId, String filename,
                             String chunkId, int startOffset, int endOffset, double score) {
            this.content = content;
            this.docId = docId;
            this.filename = filename;
            this.chunkId = chunkId;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.score = score;
        }

        public String getContent() { return content; }
        public String getDocId() { return docId; }
        public String getFilename() { return filename; }
        public String getChunkId() { return chunkId; }
        public int getStartOffset() { return startOffset; }
        public int getEndOffset() { return endOffset; }
        public double getScore() { return score; }
    }

    /**
     * 带来源信息的递归检索结果
     */
    class RecursiveSearchResultWithSources {
        private final List<SourcedContext> sourcedContexts;
        private final int retrievalDepth;
        private final RetrievalStrategy primaryStrategy;
        private final List<RetrievalStep> steps;
        private final double finalConfidence;
        private final String explanation;

        public RecursiveSearchResultWithSources(
                List<SourcedContext> sourcedContexts, int retrievalDepth,
                RetrievalStrategy primaryStrategy, List<RetrievalStep> steps,
                double finalConfidence, String explanation) {
            this.sourcedContexts = sourcedContexts;
            this.retrievalDepth = retrievalDepth;
            this.primaryStrategy = primaryStrategy;
            this.steps = steps;
            this.finalConfidence = finalConfidence;
            this.explanation = explanation;
        }

        public List<SourcedContext> getSourcedContexts() { return sourcedContexts; }
        public int getRetrievalDepth() { return retrievalDepth; }
        public RetrievalStrategy getPrimaryStrategy() { return primaryStrategy; }
        public List<RetrievalStep> getSteps() { return steps; }
        public double getFinalConfidence() { return finalConfidence; }
        public String getExplanation() { return explanation; }
    }
    
    /**
     * 检索步骤数据类
     */
    class RetrievalStep {
        private int stepNumber;
        private String query;
        private RetrievalStrategy strategy;
        private List<String> retrievedContexts;
        private double confidence;
        private String reasoning;
        
        public RetrievalStep(int stepNumber, String query, RetrievalStrategy strategy,
                            List<String> retrievedContexts, double confidence, String reasoning) {
            this.stepNumber = stepNumber;
            this.query = query;
            this.strategy = strategy;
            this.retrievedContexts = retrievedContexts;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
        
        // Getters and setters
        public int getStepNumber() { return stepNumber; }
        public void setStepNumber(int stepNumber) { this.stepNumber = stepNumber; }
        
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        
        public RetrievalStrategy getStrategy() { return strategy; }
        public void setStrategy(RetrievalStrategy strategy) { this.strategy = strategy; }
        
        public List<String> getRetrievedContexts() { return retrievedContexts; }
        public void setRetrievedContexts(List<String> retrievedContexts) { this.retrievedContexts = retrievedContexts; }
        
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        
        public String getReasoning() { return reasoning; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    }
    
    /**
     * 检索策略枚举
     */
    enum RetrievalStrategy {
        DIRECT,         // 直接检索
        STEP_BACK,      // Step-Back策略
        HYDE,           // HyDE策略
        DECOMPOSITION,  // 问题分解策略
        HYBRID          // 混合策略
    }
}
