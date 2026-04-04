package seislearner.service.impl;

import seislearner.mapper.ChunkBgeM3Mapper;
import seislearner.model.entity.ChunkBgeM3;
import seislearner.service.HybridRetrievalService;
import seislearner.service.RagService;
import seislearner.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HybridRetrievalServiceImpl implements HybridRetrievalService {
    
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RerankService rerankService;
    
    // 默认权重：稠密检索70%，稀疏检索30%
    private float denseWeight = 0.7f;
    private float sparseWeight = 0.3f;
    
    public HybridRetrievalServiceImpl(RagService ragService, ChunkBgeM3Mapper chunkBgeM3Mapper, RerankService rerankService) {
        this.ragService = ragService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.rerankService = rerankService;
    }
    
    @Override
    public List<String> hybridSearch(String kbId, String query, int limit) {
        List<RetrievalResult> results = hybridSearchDetailed(kbId, query, limit);
        return results.stream()
                .map(RetrievalResult::getContent)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<RetrievalResult> hybridSearchDetailed(String kbId, String query, int limit) {
        log.info("执行混合检索: kbId={}, query={}, limit={}", kbId, query, limit);
        
        // 1. 稠密向量检索 (qwen3-vl-embedding)
        List<RetrievalResult> denseResults = denseSearch(kbId, query, limit * 2);
        
        // 2. 稀疏全文检索（PostgreSQL tsvector）
        List<RetrievalResult> sparseResults = sparseSearch(kbId, query, limit * 2);
        
        // 3. 合并稠密+稀疏结果
        List<RetrievalResult> mergedResults = mergeResults(denseResults, sparseResults);
        
        // 4. Rerank 精排（qwen3-vl-rerank）—— 关键步骤！
        List<RetrievalResult> rerankedResults;
        if (!mergedResults.isEmpty()) {
            List<String> documents = mergedResults.stream()
                .map(RetrievalResult::getContent).toList();
            rerankedResults = applyRerank(query, documents, mergedResults, limit);
        } else {
            rerankedResults = Collections.emptyList();
        }
        
        log.info("混合检索完成: 稠密结果数={}, 稀疏结果数={}, 合并数={}, 最终精排={}", 
                denseResults.size(), sparseResults.size(), mergedResults.size(), rerankedResults.size());
        
        return rerankedResults;
    }
    
    @Override
    public List<RetrievalResult> denseSearch(String kbId, String query, int limit) {
        try {
            // 使用现有的RagService进行向量检索
            float[] embedding = ragService.embed(query);
            String vectorLiteral = toPgVector(embedding);
            
            List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, vectorLiteral, limit);
            
            List<RetrievalResult> results = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkBgeM3 chunk = chunks.get(i);
                // 使用余弦相似度计算分数（与HNSW索引的vector_cosine_ops匹配）
                double similarityScore = calculateCosineSimilarity(embedding, chunk.getEmbedding());
                // 确保相似度在合理范围内
                similarityScore = Math.max(0.0, Math.min(1.0, similarityScore));
                results.add(new RetrievalResult(
                    chunk.getContent(),
                    chunk.getId(),
                    chunk.getDocId(),
                    similarityScore,  // 稠密分数
                    0.0,             // 稀疏分数
                    RetrievalType.DENSE
                ));
            }
            
            return results;
        } catch (Exception e) {
            log.error("稠密检索失败", e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public List<RetrievalResult> sparseSearch(String kbId, String query, int limit) {
        // 基于 PostgreSQL tsvector 的全文检索（稀疏/BM25）
        try {
            // 中文查询预处理：按常见分隔符拆分关键词，构造 OR 格式的 tsquery
            String tsquery = buildOrTsQuery(query);
            if (tsquery.isEmpty()) {
                log.debug("稀疏检索查询预处理为空，跳过");
                return Collections.emptyList();
            }
            
            List<Map<String, Object>> ftResults = chunkBgeM3Mapper.fullTextSearch(kbId, tsquery, limit);
            
            List<RetrievalResult> results = new ArrayList<>();
            for (Map<String, Object> row : ftResults) {
                String chunkId = (String) row.get("chunkId");
                String docId = (String) row.get("docId");
                String content = (String) row.get("content");
                Object rankObj = row.get("rank");
                double rank = (rankObj instanceof Number) ? ((Number) rankObj).doubleValue() : 0.0;
                
                // ts_rank_cd 返回值通常在 0~1 范围，但无上限，做归一化
                // 使用 tanh 压缩到 [0, 1) 区间，避免极端值
                double normalizedRank = Math.tanh(rank);
                
                results.add(new RetrievalResult(
                    content,
                    chunkId,
                    docId,
                    0.0,                // 稠密分数（稀疏检索不产生）
                    normalizedRank,     // 稀疏分数（归一化后的 ts_rank_cd）
                    RetrievalType.SPARSE
                ));
            }
            
            log.info("稀疏全文检索完成: query={}, tsquery={}, 结果数={}", 
                query.length() > 30 ? query.substring(0, 30) + "..." : query, 
                tsquery.length() > 50 ? tsquery.substring(0, 50) + "..." : tsquery,
                results.size());
            return results;
            
        } catch (Exception e) {
            log.error("稀疏全文检索失败，降级返回空", e);
            return Collections.emptyList();
        }
    }

    /**
     * 将中文查询字符串构造为 OR 格式的 PostgreSQL tsquery
     * 策略：按非字母数字中文字符拆分，生成 '词1 | 词2 | 词3' 格式
     * 例如 "地震波运动学理论" → "地震波运动学理论 | 地震波运动学 | 地震波 | 地震"
     * 即生成所有前缀子串（从完整到2字），增加召回率
     */
    private String buildOrTsQuery(String query) {
        if (query == null || query.isBlank()) return "";
        
        // 去掉标点和空白，只保留有意义的字符
        String cleaned = query.replaceAll("[\\s\\p{Punct}？？!！。，,、；;：:\"\"''（）()【】\\[\\]{}]", " ").trim();
        if (cleaned.isEmpty()) return "";
        
        // 按空格拆分为多个词组
        String[] segments = cleaned.split("\\s+");
        
        Set<String> terms = new LinkedHashSet<>();
        
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            
            // 提取连续中文片段和英文/数字片段
            StringBuilder currentToken = new StringBuilder();
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                boolean isChinese = isChinese(c);
                boolean isAlnum = Character.isLetterOrDigit(c);
                
                if (isChinese || isAlnum) {
                    currentToken.append(c);
                } else {
                    if (currentToken.length() > 0) {
                        extractTerms(currentToken.toString(), terms);
                        currentToken = new StringBuilder();
                    }
                }
            }
            if (currentToken.length() > 0) {
                extractTerms(currentToken.toString(), terms);
            }
        }
        
        if (terms.isEmpty()) return "";
        
        // 构造 OR 格式的 tsquery：'词1' | '词2' | '词3'
        StringBuilder tsquery = new StringBuilder();
        for (String term : terms) {
            if (tsquery.length() > 0) {
                tsquery.append(" | ");
            }
            // 转义单引号
            String escaped = term.replace("'", "''");
            tsquery.append("'").append(escaped).append("'");
        }
        
        return tsquery.toString();
    }

    /**
     * 从一个 token 中提取有意义的检索词（中文：2字~全长前缀；英文：整个词）
     */
    private void extractTerms(String token, Set<String> terms) {
        if (token.length() <= 1) {
            // 单字也加入，可能有价值
            if (isChinese(token.charAt(0)) && token.length() == 1) {
                // 中文单字单独加（如"波"在地震学中有意义）
                // 但为了减少噪音，只保留长度>=2的词
                return;
            }
            if (token.length() == 1) terms.add(token);
            return;
        }
        
        boolean hasChinese = false;
        for (int i = 0; i < token.length(); i++) {
            if (isChinese(token.charAt(i))) { hasChinese = true; break; }
        }
        
        if (hasChinese) {
            // 中文混合词：提取2字到全长所有前缀
            // 例如 "地震波运动学" → ["地震", "地震波", "地震波运", "地震波运动", "地震波运动学"]
            for (int len = 2; len <= token.length(); len++) {
                terms.add(token.substring(0, len));
            }
            // 也加入全长
            terms.add(token);
        } else {
            // 纯英文/数字：整个词加入
            terms.add(token.toLowerCase());
        }
    }

    /**
     * 判断字符是否是 CJK 统一汉字
     */
    private boolean isChinese(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
    
    @Override
    public void setWeights(float denseWeight, float sparseWeight) {
        if (denseWeight + sparseWeight != 1.0f) {
            log.warn("权重之和不为1，已归一化");
            float total = denseWeight + sparseWeight;
            this.denseWeight = denseWeight / total;
            this.sparseWeight = sparseWeight / total;
        } else {
            this.denseWeight = denseWeight;
            this.sparseWeight = sparseWeight;
        }
        log.info("设置混合检索权重: 稠密={}, 稞疏={}", this.denseWeight, this.sparseWeight);
    }
    
    /**
     * 合并稠密和稀疏检索结果
     */
    private List<RetrievalResult> mergeResults(List<RetrievalResult> denseResults, 
                                               List<RetrievalResult> sparseResults) {
        Map<String, RetrievalResult> mergedMap = new HashMap<>();
        
        // 添加稠密结果
        for (RetrievalResult result : denseResults) {
            String key = result.getChunkId();
            if (!mergedMap.containsKey(key)) {
                mergedMap.put(key, result);
            } else {
                // 如果已存在，更新稠密分数
                RetrievalResult existing = mergedMap.get(key);
                existing.setDenseScore(result.getDenseScore());
                existing.setFinalScore(calculateFinalScore(existing));
            }
        }
        
        // 添加稀疏结果
        for (RetrievalResult result : sparseResults) {
            String key = result.getChunkId();
            if (!mergedMap.containsKey(key)) {
                mergedMap.put(key, result);
            } else {
                // 如果已存在，更新稀疏分数
                RetrievalResult existing = mergedMap.get(key);
                existing.setSparseScore(result.getSparseScore());
                existing.setRetrievalType(RetrievalType.HYBRID);
                existing.setFinalScore(calculateFinalScore(existing));
            }
        }
        
        // 计算最终分数并排序
        List<RetrievalResult> mergedList = new ArrayList<>(mergedMap.values());
        for (RetrievalResult result : mergedList) {
            result.setFinalScore(calculateFinalScore(result));
        }
        
        mergedList.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        
        return mergedList;
    }
    
    /**
     * 计算最终分数（加权平均）
     */
    private double calculateFinalScore(RetrievalResult result) {
        return result.getDenseScore() * denseWeight + result.getSparseScore() * sparseWeight;
    }
    
    /**
     * 去重并限制结果数量
     */
    private List<RetrievalResult> deduplicateAndLimit(List<RetrievalResult> results, int limit) {
        // 基于内容相似度去重（简化版：基于chunkId去重）
        Map<String, RetrievalResult> uniqueMap = new LinkedHashMap<>();
        for (RetrievalResult result : results) {
            String key = result.getChunkId();
            if (!uniqueMap.containsKey(key)) {
                uniqueMap.put(key, result);
            }
        }
        
        List<RetrievalResult> uniqueResults = new ArrayList<>(uniqueMap.values());
        
        if (uniqueResults.size() > limit) {
            return uniqueResults.subList(0, limit);
        }
        
        return uniqueResults;
    }
    
    /**
     * 计算余弦相似度（替代欧氏距离，与HNSW索引的vector_cosine_ops匹配）
     */
    private double calculateCosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            return 0.0;
        }
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        // 数值稳定性检查：避免除以接近零的值
        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);
        if (denominator < 1e-10) {
            return 0.0;
        }
        
        return dotProduct / denominator;
    }
    
    /**
     * 将向量转换为PostgreSQL向量字面量
     */
    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 应用 Rerank 精排，将 rerank 分数融合到最终分数中
     */
    private List<RetrievalResult> applyRerank(String query, List<String> documents,
                                              List<RetrievalResult> mergedResults, int limit) {
        try {
            List<RerankService.RerankResult> rerankResults = rerankService.rerank(query, documents, limit);
            
            if (rerankResults.isEmpty()) {
                log.info("Rerank 返回空结果，使用合并后的原始排序");
                return deduplicateAndLimit(mergedResults, limit);
            }

            // 用 rerank 结果构建最终列表
            List<RetrievalResult> finalResults = new ArrayList<>();
            for (RerankService.RerankResult rr : rerankResults) {
                int origIdx = rr.getOriginalIndex();
                if (origIdx >= 0 && origIdx < mergedResults.size()) {
                    RetrievalResult original = mergedResults.get(origIdx);
                    // 最终分数 = 0.3 * (dense+sparse混合) + 0.7 * rerank精排分数
                    double hybridScore = original.getDenseScore() * denseWeight 
                        + original.getSparseScore() * sparseWeight;
                    double finalScore = hybridScore * 0.3 + rr.getScore() * 0.7;
                    
                    finalResults.add(new RetrievalResult(
                        rr.getContent(),
                        original.getChunkId(),
                        original.getDocId(),
                        finalScore,
                        rr.getScore(),          // rerank 精排分数
                        RetrievalType.HYBRID    // 经过精排的混合结果
                    ));
                }
            }
            
            log.info("Rerank 精排完成: 输入={}, 输出={}", documents.size(), finalResults.size());
            return finalResults;
            
        } catch (Exception e) {
            log.error("Rerank 精排失败，降级使用混合检索结果", e);
            return deduplicateAndLimit(mergedResults, limit);
        }
    }
}
