package seislearner.service;

import java.util.List;
import java.util.Map;

/**
 * 混合向量检索服务接口
 * 结合稠密向量（HNSW）和稀疏向量（BM25）检索
 */
public interface HybridRetrievalService {
    
    /**
     * 混合检索：结合向量相似性和文本相关性
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param limit 返回结果数量
     * @return 检索到的文本内容列表
     */
    List<String> hybridSearch(String kbId, String query, int limit);
    
    /**
     * 混合检索，返回详细结果（包含分数和来源信息）
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param limit 返回结果数量
     * @return 检索结果列表，包含内容、分数和检索类型
     */
    List<RetrievalResult> hybridSearchDetailed(String kbId, String query, int limit);
    
    /**
     * 仅使用稠密向量检索（HNSW）
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param limit 返回结果数量
     * @return 检索结果列表
     */
    List<RetrievalResult> denseSearch(String kbId, String query, int limit);
    
    /**
     * 仅使用稀疏向量检索（BM25）
     *
     * @param kbId 知识库ID
     * @param query 查询文本
     * @param limit 返回结果数量
     * @return 检索结果列表
     */
    List<RetrievalResult> sparseSearch(String kbId, String query, int limit);
    
    /**
     * 设置混合权重
     *
     * @param denseWeight 稠密检索权重（0-1）
     * @param sparseWeight 稀疏检索权重（0-1）
     */
    void setWeights(float denseWeight, float sparseWeight);
    
    /**
     * 检索结果数据类
     */
    class RetrievalResult {
        private String content;
        private String chunkId;
        private String docId;
        private double denseScore;
        private double sparseScore;
        private double finalScore;
        private RetrievalType retrievalType;
        
        public RetrievalResult(String content, String chunkId, String docId, 
                              double denseScore, double sparseScore, 
                              RetrievalType retrievalType) {
            this.content = content;
            this.chunkId = chunkId;
            this.docId = docId;
            this.denseScore = denseScore;
            this.sparseScore = sparseScore;
            this.finalScore = calculateFinalScore(denseScore, sparseScore);
            this.retrievalType = retrievalType;
        }
        
        private double calculateFinalScore(double denseScore, double sparseScore) {
            // 默认使用加权平均，权重在服务层设置
            return denseScore * 0.7 + sparseScore * 0.3;
        }
        
        // Getters and setters
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        
        public double getDenseScore() { return denseScore; }
        public void setDenseScore(double denseScore) { this.denseScore = denseScore; }
        
        public double getSparseScore() { return sparseScore; }
        public void setSparseScore(double sparseScore) { this.sparseScore = sparseScore; }
        
        public double getFinalScore() { return finalScore; }
        public void setFinalScore(double finalScore) { this.finalScore = finalScore; }
        
        public RetrievalType getRetrievalType() { return retrievalType; }
        public void setRetrievalType(RetrievalType retrievalType) { this.retrievalType = retrievalType; }
    }
    
    /**
     * 检索类型枚举
     */
    enum RetrievalType {
        DENSE,      // 稠密向量检索
        SPARSE,     // 稀疏向量检索
        HYBRID      // 混合检索
    }
}
