package seislearner.service.impl;

import seislearner.mapper.ChunkBgeM3Mapper;
import seislearner.mapper.DocumentMapper;
import seislearner.model.entity.ChunkBgeM3;
import seislearner.model.entity.Document;
import seislearner.service.HybridRetrievalService;
import seislearner.service.RecursiveRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class RecursiveRetrievalServiceImpl implements RecursiveRetrievalService {
    
    private final HybridRetrievalService hybridRetrievalService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final DocumentMapper documentMapper;
    
    // 最大递归深度
    private static final int MAX_RETRIEVAL_DEPTH = 4;
    // 置信度阈值：首轮直接检索如果超过此阈值则不需要递归（0.5更容易触发递归，检索更广）
    private static final double CONFIDENCE_THRESHOLD = 0.5;
    // 单条 chunk 在累积结果中的最大字符数（1000字可展示更完整的上下文片段）
    private static final int MAX_CHUNK_CHARS_IN_RESULT = 1000;
    // 每轮检索数量
    private static final int PER_ROUND_LIMIT = 8;
    // 累积结果最大条数（即使4轮最多也只保留这么多）
    private static final int MAX_TOTAL_RESULTS = 15;
    
    // 文件名缓存（同一检索过程中同一 docId 不重复查库）
    private final Map<String, String> filenameCache = new HashMap<>();
    
    public RecursiveRetrievalServiceImpl(HybridRetrievalService hybridRetrievalService,
                                         ChunkBgeM3Mapper chunkBgeM3Mapper,
                                         DocumentMapper documentMapper) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.documentMapper = documentMapper;
    }
    
    /**
     * 根据 docId 查询文件名，带缓存
     */
    private String resolveFilename(String docId) {
        if (docId == null) return "未知文档";
        return filenameCache.computeIfAbsent(docId, id -> {
            try {
                Document doc = documentMapper.selectById(id);
                return doc != null ? doc.getFilename() : "未知文档";
            } catch (Exception e) {
                log.warn("查询文件名失败: docId={}", id, e);
                return "未知文档";
            }
        });
    }
    
    @Override
    public List<String> recursiveSearch(String kbId, String query, int initialLimit) {
        RecursiveSearchResult result = recursiveSearchDetailed(kbId, query, initialLimit);
        return result.getContexts();
    }
    
    @Override
    public RecursiveSearchResult recursiveSearchDetailed(String kbId, String query, int initialLimit) {
        RecursiveSearchResultWithSources sourced = recursiveSearchDetailedWithSources(kbId, query, initialLimit, 0);
        
        // 转换为旧格式（向后兼容）
        List<String> contexts = new ArrayList<>();
        for (SourcedContext sc : sourced.getSourcedContexts()) {
            contexts.add(sc.getContent());
        }
        
        return new RecursiveSearchResult(
            contexts,
            sourced.getRetrievalDepth(),
            sourced.getPrimaryStrategy(),
            sourced.getSteps(),
            sourced.getFinalConfidence(),
            sourced.getExplanation()
        );
    }

    @Override
    public RecursiveSearchResultWithSources recursiveSearchDetailedWithSources(
            String kbId, String query, int initialLimit, int maxTotalChars) {
        
        log.info("开始递归检索(带来源): kbId={}, query={}, limit={}, maxChars={}", 
                kbId, query, initialLimit, maxTotalChars);
        
        // 使用 LinkedHashSet 按 chunkId 去重，保持插入顺序
        Map<String, SourcedContext> uniqueContexts = new LinkedHashMap<>();
        List<RetrievalStep> steps = new ArrayList<>();
        double finalConfidence = 0.0;
        RetrievalStrategy primaryStrategy = RetrievalStrategy.DIRECT;
        String explanation = "";
        
        // 第一步：直接检索
        List<HybridRetrievalService.RetrievalResult> directResults =
                hybridRetrievalService.hybridSearchDetailed(kbId, query, PER_ROUND_LIMIT);
        
        if (directResults.isEmpty()) {
            log.info("直接检索无结果，返回空");
            return new RecursiveSearchResultWithSources(
                List.of(), 0, RetrievalStrategy.DIRECT, steps, 0.0, "直接检索无结果");
        }
        
        // 评估首轮置信度
        List<String> directContexts = directResults.stream()
                .map(HybridRetrievalService.RetrievalResult::getContent).toList();
        double firstConfidence = assessConfidence(directContexts, query);
        primaryStrategy = RetrievalStrategy.DIRECT;
        
        // 添加首轮结果
        addToUniqueContexts(uniqueContexts, directResults, maxTotalChars);
        
        steps.add(new RetrievalStep(1, query, RetrievalStrategy.DIRECT,
                directContexts, firstConfidence,
                String.format("直接HNSW+Rerank检索, 置信度=%.2f", firstConfidence)));
        
        log.info("首轮检索完成: 结果数={}, 置信度={}", directResults.size(), firstConfidence);
        
        // 如果首轮置信度足够高，直接返回
        if (firstConfidence >= CONFIDENCE_THRESHOLD) {
            finalConfidence = firstConfidence;
            explanation = String.format("直接检索置信度达标(%.2f)，无需递归", firstConfidence);
            return buildResult(uniqueContexts, 1, primaryStrategy, steps, finalConfidence, explanation, maxTotalChars);
        }
        
        // 第二步：根据查询复杂度决定是否递归
        double complexity = assessQueryComplexity(query);
        
        // 简单查询且首轮有一定结果，不递归
        if (complexity < 0.3 && !directResults.isEmpty()) {
            explanation = String.format("简单查询，直接检索返回%d条结果", directResults.size());
            return buildResult(uniqueContexts, 1, primaryStrategy, steps, firstConfidence, explanation, maxTotalChars);
        }
        
        // 第三步：递归检索（最多 MAX_RETRIEVAL_DEPTH - 1 轮，因为第一轮已经做了）
        String currentQuery = query;
        RetrievalStrategy currentStrategy = selectStrategyForRecursion(complexity, firstConfidence);
        
        for (int round = 2; round <= MAX_RETRIEVAL_DEPTH; round++) {
            // 检查是否已达到预算
            if (maxTotalChars > 0 && estimateTotalChars(uniqueContexts) > maxTotalChars * 0.8) {
                log.info("已接近字符预算，停止递归");
                explanation = String.format("字符预算限制，在第%d轮停止", round - 1);
                break;
            }
            
            // 检查是否已达到结果数上限
            if (uniqueContexts.size() >= MAX_TOTAL_RESULTS) {
                log.info("已达到最大结果数{}，停止递归", MAX_TOTAL_RESULTS);
                explanation = String.format("已达最大结果数%d", MAX_TOTAL_RESULTS);
                break;
            }
            
            // 根据策略调整查询
            String adjustedQuery = adjustQueryByStrategy(currentQuery, currentStrategy);
            log.info("递归第{}轮: 策略={}, 查询={}", round, currentStrategy, adjustedQuery);
            
            // 执行检索
            List<HybridRetrievalService.RetrievalResult> roundResults =
                    hybridRetrievalService.hybridSearchDetailed(kbId, adjustedQuery, PER_ROUND_LIMIT);
            
            if (roundResults.isEmpty()) {
                log.info("第{}轮检索无结果", round);
                // 尝试切换策略
                currentStrategy = nextStrategy(currentStrategy);
                continue;
            }
            
            // 评估本轮置信度
            List<String> roundContexts = roundResults.stream()
                    .map(HybridRetrievalService.RetrievalResult::getContent).toList();
            double roundConfidence = assessConfidence(roundContexts, adjustedQuery);
            
            // 去重添加
            int addedCount = addToUniqueContexts(uniqueContexts, roundResults, maxTotalChars);
            
            steps.add(new RetrievalStep(round, adjustedQuery, currentStrategy,
                    roundContexts, roundConfidence,
                    String.format("递归检索, 新增%d条(去重后), 累计%d条",
                            addedCount, uniqueContexts.size())));
            
            log.info("第{}轮完成: 新增={}, 累计={}, 置信度={}",
                    round, addedCount, uniqueContexts.size(), roundConfidence);
            
            // 更新最佳置信度
            finalConfidence = Math.max(finalConfidence, roundConfidence);
            
            // 如果置信度达标，停止
            if (roundConfidence >= CONFIDENCE_THRESHOLD) {
                explanation = String.format("第%d轮递归后置信度达标(%.2f)", round, roundConfidence);
                break;
            }
            
            // 准备下一轮
            currentQuery = generateNextQuery(currentQuery, roundResults, currentStrategy);
            currentStrategy = nextStrategy(currentStrategy);
        }
        
        if (explanation.isEmpty()) {
            explanation = String.format("完成%d轮检索，最终置信度%.2f", 
                    steps.size(), finalConfidence);
        }
        
        int depth = steps.size();
        return buildResult(uniqueContexts, depth, primaryStrategy, steps, finalConfidence, explanation, maxTotalChars);
    }

    /**
     * 构建最终结果，应用字符截断
     */
    private RecursiveSearchResultWithSources buildResult(
            Map<String, SourcedContext> uniqueContexts, int depth,
            RetrievalStrategy primaryStrategy, List<RetrievalStep> steps,
            double finalConfidence, String explanation, int maxTotalChars) {
        
        List<SourcedContext> resultList = new ArrayList<>(uniqueContexts.values());
        
        // 按分数降序排列
        resultList.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        // 应用字符预算截断
        if (maxTotalChars > 0) {
            resultList = truncateToBudget(resultList, maxTotalChars);
        }
        
        // 限制最大条数
        if (resultList.size() > MAX_TOTAL_RESULTS) {
            resultList = resultList.subList(0, MAX_TOTAL_RESULTS);
        }
        
        return new RecursiveSearchResultWithSources(
                resultList, depth, primaryStrategy, steps, finalConfidence, explanation);
    }

    /**
     * 将检索结果去重添加到累积 map 中
     * @return 新增的条数
     */
    private int addToUniqueContexts(Map<String, SourcedContext> uniqueContexts,
                                     List<HybridRetrievalService.RetrievalResult> results,
                                     int maxTotalChars) {
        int added = 0;
        for (HybridRetrievalService.RetrievalResult r : results) {
            String key = r.getChunkId();
            if (!uniqueContexts.containsKey(key)) {
                // 截断内容
                String content = truncateWithEllipsis(r.getContent(), MAX_CHUNK_CHARS_IN_RESULT);
                String filename = resolveFilename(r.getDocId());

                // 查询 chunk 的偏移信息
                int startOffset = 0;
                int endOffset = 0;
                try {
                    ChunkBgeM3 chunk = chunkBgeM3Mapper.selectById(r.getChunkId());
                    if (chunk != null) {
                        if (chunk.getStartOffset() != null) startOffset = chunk.getStartOffset();
                        if (chunk.getEndOffset() != null) endOffset = chunk.getEndOffset();
                    }
                } catch (Exception e) {
                    log.debug("查询chunk偏移失败: chunkId={}", r.getChunkId());
                }

                uniqueContexts.put(key, new SourcedContext(
                        content, r.getDocId(), filename,
                        r.getChunkId(), startOffset, endOffset, r.getFinalScore()));
                added++;
            }
        }
        return added;
    }

    /**
     * 估算累积结果的字符数
     */
    private int estimateTotalChars(Map<String, SourcedContext> contexts) {
        int total = 0;
        for (SourcedContext sc : contexts.values()) {
            total += sc.getContent().length();
        }
        return total;
    }

    /**
     * 按字符预算截断结果列表
     */
    private List<SourcedContext> truncateToBudget(List<SourcedContext> list, int maxTotalChars) {
        List<SourcedContext> truncated = new ArrayList<>();
        int used = 0;
        for (SourcedContext sc : list) {
            if (used + sc.getContent().length() > maxTotalChars && !truncated.isEmpty()) {
                break;
            }
            truncated.add(sc);
            used += sc.getContent().length();
        }
        return truncated;
    }

    /**
     * 截断文本，但保留图片链接
     * 如果图片链接在截断位置之后，会将其提取并附加到末尾
     */
    private String truncateWithEllipsis(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        
        // 提取所有图片链接
        java.util.regex.Pattern imagePattern = java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\([^)]+\\)");
        java.util.regex.Matcher matcher = imagePattern.matcher(text);
        
        // 收集所有图片链接
        java.util.List<String> allImages = new java.util.ArrayList<>();
        while (matcher.find()) {
            allImages.add(matcher.group());
        }
        
        // 截断文本
        String truncated = text.substring(0, limit);
        
        // 找出被截断的图片链接（在截断位置之后的）
        java.util.List<String> lostImages = new java.util.ArrayList<>();
        for (String img : allImages) {
            if (!truncated.contains(img)) {
                lostImages.add(img);
            }
        }
        
        // 如果有图片被截断，附加到末尾
        if (!lostImages.isEmpty()) {
            StringBuilder sb = new StringBuilder(truncated);
            sb.append("...\n\n**相关图片:**\n");
            for (String img : lostImages) {
                sb.append(img).append("\n");
            }
            return sb.toString();
        }
        
        return truncated + "...";
    }

    /**
     * 根据复杂度和首轮置信度选择递归策略
     */
    private RetrievalStrategy selectStrategyForRecursion(double complexity, double firstConfidence) {
        if (complexity > 0.6) {
            return RetrievalStrategy.DECOMPOSITION;  // 复杂查询：问题分解
        } else if (firstConfidence < 0.3) {
            return RetrievalStrategy.STEP_BACK;     // 结果很差：回退到更高层次
        } else {
            return RetrievalStrategy.HYDE;           // 一般：用假设答案改写查询
        }
    }

    /**
     * 策略轮换顺序
     */
    private RetrievalStrategy nextStrategy(RetrievalStrategy current) {
        return switch (current) {
            case HYDE -> RetrievalStrategy.STEP_BACK;
            case STEP_BACK -> RetrievalStrategy.DECOMPOSITION;
            case DECOMPOSITION -> RetrievalStrategy.HYBRID;
            default -> RetrievalStrategy.HYBRID;
        };
    }
    
    @Override
    public String stepBackQuery(String originalQuery) {
        String[] stepBackPrefixes = {
            "什么是", "解释", "概述", "总结", "讨论"
        };
        
        Random random = new Random();
        String prefix = stepBackPrefixes[random.nextInt(stepBackPrefixes.length)];
        
        String simplifiedQuery = originalQuery
                .replace("？", "")
                .replace("?", "");
        
        return prefix + " " + simplifiedQuery;
    }
    
    @Override
    public String generateHypotheticalAnswer(String query) {
        return String.format("基于%s的知识，可能的答案是：这是一个与%s相关的问题，需要考虑多个方面", query, query);
    }
    
    @Override
    public List<String> decomposeQuery(String complexQuery) {
        List<String> subQueries = new ArrayList<>();
        
        String[] connectors = {"和", "或", "以及", "、", "还是"};
        boolean decomposed = false;
        
        for (String connector : connectors) {
            if (complexQuery.contains(connector)) {
                String[] parts = complexQuery.split(connector);
                for (String part : parts) {
                    if (part.trim().length() > 2) {
                        subQueries.add(part.trim() + "?");
                    }
                }
                decomposed = true;
                break;
            }
        }
        
        if (!decomposed && complexQuery.length() > 20) {
            int mid = complexQuery.length() / 2;
            subQueries.add(complexQuery.substring(0, mid) + "?");
            subQueries.add(complexQuery.substring(mid) + "?");
        }
        
        if (subQueries.isEmpty()) {
            subQueries.add(complexQuery);
        }
        
        return subQueries;
    }
    
    @Override
    public double assessQueryComplexity(String query) {
        if (query == null || query.trim().isEmpty()) {
            return 0.0;
        }
        
        double complexity = 0.0;
        
        int length = query.length();
        if (length > 50) complexity += 0.4;
        else if (length > 30) complexity += 0.3;
        else if (length > 15) complexity += 0.2;
        else complexity += 0.1;
        
        String[] words = query.split("\\s+");
        if (words.length > 5) complexity += 0.3;
        else if (words.length > 3) complexity += 0.2;
        else complexity += 0.1;
        
        if (query.contains("和") || query.contains("或") || query.contains("以及") || 
            query.contains("、") || query.contains("还是")) {
            complexity += 0.2;
        }
        
        if (query.contains("?") || query.contains("？") || 
            query.contains("什么") || query.contains("如何") || query.contains("为什么")) {
            complexity += 0.1;
        }
        
        return Math.min(complexity, 1.0);
    }
    
    @Override
    public double assessConfidence(List<String> retrievedContexts, String query) {
        if (retrievedContexts == null || retrievedContexts.isEmpty()) {
            return 0.0;
        }
        
        double confidence = 0.0;
        
        int contextCount = retrievedContexts.size();
        if (contextCount >= 5) confidence += 0.4;
        else if (contextCount >= 3) confidence += 0.3;
        else if (contextCount >= 1) confidence += 0.2;
        
        int totalLength = 0;
        for (String context : retrievedContexts) {
            totalLength += context.length();
        }
        
        if (totalLength > 1000) confidence += 0.3;
        else if (totalLength > 500) confidence += 0.2;
        else if (totalLength > 100) confidence += 0.1;
        
        String[] queryWords = query.toLowerCase().split("\\s+");
        int matchedWords = 0;
        
        for (String word : queryWords) {
            if (word.length() > 1) {
                for (String context : retrievedContexts) {
                    if (context.toLowerCase().contains(word)) {
                        matchedWords++;
                        break;
                    }
                }
            }
        }
        
        double matchRatio = (double) matchedWords / queryWords.length;
        confidence += matchRatio * 0.3;
        
        return Math.min(confidence, 1.0);
    }
    
    /**
     * 根据策略调整查询
     */
    private String adjustQueryByStrategy(String query, RetrievalStrategy strategy) {
        switch (strategy) {
            case STEP_BACK:
                return stepBackQuery(query);
            case HYDE:
                return generateHypotheticalAnswer(query);
            case DECOMPOSITION:
                List<String> subQueries = decomposeQuery(query);
                return subQueries.isEmpty() ? query : subQueries.get(0);
            default:
                return query;
        }
    }
    
    /**
     * 生成下一轮检索的查询
     */
    private String generateNextQuery(String currentQuery,
                                      List<HybridRetrievalService.RetrievalResult> contexts,
                                      RetrievalStrategy nextStrategy) {
        switch (nextStrategy) {
            case HYBRID:
                if (!contexts.isEmpty()) {
                    String firstContent = contexts.get(0).getContent();
                    String[] words = firstContent.split("\\s+");
                    if (words.length > 5) {
                        String keywords = String.join(" ", Arrays.copyOfRange(words, 0, 5));
                        return currentQuery + " " + keywords;
                    }
                }
                return currentQuery;
            case DECOMPOSITION:
                List<String> subQueries = decomposeQuery(currentQuery);
                if (subQueries.size() > 1) {
                    return subQueries.get(1);
                }
                return currentQuery;
            default:
                return currentQuery;
        }
    }
}
