package seislearner.service.impl;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * BM25算法计算器
 * 参考SuperMew的embedding.py实现
 */
public class Bm25Calculator {
    
    // BM25参数
    private final double k1 = 1.5;  // 词频饱和参数
    private final double b = 0.75;  // 文档长度归一化参数
    
    // 词汇表：词 -> 索引
    private final Map<String, Integer> vocab = new HashMap<>();
    private int vocabCounter = 0;
    
    // 文档频率统计：词 -> 出现该词的文档数
    private final Map<String, Integer> docFreq = new HashMap<>();
    private int totalDocs = 0;
    private double avgDocLen = 0.0;
    
    // 正则表达式模式
    private final Pattern chinesePattern = Pattern.compile("[\u4e00-\u9fff]");
    private final Pattern englishPattern = Pattern.compile("[a-zA-Z]+");
    
    /**
     * 拟合语料库，计算IDF和平均文档长度
     * @param documents 文档列表
     */
    public void fitCorpus(List<String> documents) {
        this.totalDocs = documents.size();
        int totalLen = 0;
        
        for (String doc : documents) {
            List<String> tokens = tokenize(doc);
            totalLen += tokens.size();
            
            // 统计文档频率（每个词在多少文档中出现）
            Set<String> uniqueTokens = new HashSet<>(tokens);
            for (String token : uniqueTokens) {
                docFreq.put(token, docFreq.getOrDefault(token, 0) + 1);
                
                // 建立词汇表索引
                if (!vocab.containsKey(token)) {
                    vocab.put(token, vocabCounter++);
                }
            }
        }
        
        this.avgDocLen = totalDocs > 0 ? (double) totalLen / totalDocs : 1.0;
    }
    
    /**
     * 简单分词器 - 支持中英文混合
     * @param text 输入文本
     * @return 分词结果
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        
        String lowerText = text.toLowerCase();
        List<String> tokens = new ArrayList<>();
        
        int i = 0;
        while (i < lowerText.length()) {
            char ch = lowerText.charAt(i);
            
            // 检查是否中文字符
            String charStr = String.valueOf(ch);
            if (chinesePattern.matcher(charStr).find()) {
                // 中文字符单独作为一个token
                tokens.add(charStr);
                i++;
            } else {
                // 检查是否英文单词
                Matcher englishMatcher = englishPattern.matcher(lowerText.substring(i));
                if (englishMatcher.lookingAt()) {
                    String word = englishMatcher.group();
                    tokens.add(word);
                    i += word.length();
                } else {
                    i++; // 跳过非中英文字符
                }
            }
        }
        
        return tokens;
    }
    
    /**
     * 计算词的IDF值
     * @param term 词条
     * @return IDF值
     */
    private double calculateIdf(String term) {
        int df = docFreq.getOrDefault(term, 0);
        if (df == 0) {
            // 新词，使用平滑IDF
            return Math.log((totalDocs + 1.0) / 1.0);
        } else {
            // 标准BM25 IDF公式
            return Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);
        }
    }
    
    /**
     * 为单个文档生成BM25稀疏向量
     * @param document 文档文本
     * @return 稀疏向量：索引 -> BM25分数
     */
    public Map<Integer, Double> getSparseVector(String document) {
        List<String> tokens = tokenize(document);
        int docLen = tokens.size();
        
        // 计算词频
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokens) {
            termFreq.put(token, termFreq.getOrDefault(token, 0) + 1);
        }
        
        Map<Integer, Double> sparseVector = new HashMap<>();
        
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            String term = entry.getKey();
            int freq = entry.getValue();
            
            // 获取或创建词汇表索引
            int idx = vocab.getOrDefault(term, -1);
            if (idx == -1) {
                // 新词加入词汇表
                idx = vocabCounter;
                vocab.put(term, vocabCounter++);
            }
            
            // 计算IDF
            double idf = calculateIdf(term);
            
            // 计算BM25分数
            double numerator = freq * (k1 + 1);
            double denominator = freq + k1 * (1 - b + b * docLen / Math.max(avgDocLen, 1.0));
            double score = idf * numerator / denominator;
            
            if (score > 0) {
                sparseVector.put(idx, score);
            }
        }
        
        return sparseVector;
    }
    
    /**
     * 计算查询与文档的BM25相似度
     * @param query 查询文本
     * @param document 文档文本
     * @return BM25相似度分数
     */
    public double calculateSimilarity(String query, String document) {
        // 获取查询和文档的稀疏向量
        Map<Integer, Double> queryVector = getSparseVector(query);
        Map<Integer, Double> docVector = getSparseVector(document);
        
        if (queryVector.isEmpty() || docVector.isEmpty()) {
            return 0.0;
        }
        
        // 计算点积（相似度）
        double similarity = 0.0;
        for (Map.Entry<Integer, Double> entry : queryVector.entrySet()) {
            int idx = entry.getKey();
            double queryScore = entry.getValue();
            Double docScore = docVector.get(idx);
            if (docScore != null) {
                similarity += queryScore * docScore;
            }
        }
        
        return similarity;
    }
    
    /**
     * 批量计算查询与多个文档的相似度
     * @param query 查询文本
     * @param documents 文档列表
     * @return 相似度分数列表
     */
    public List<Double> batchCalculateSimilarity(String query, List<String> documents) {
        // 先获取查询向量
        Map<Integer, Double> queryVector = getSparseVector(query);
        if (queryVector.isEmpty()) {
            return Collections.nCopies(documents.size(), 0.0);
        }
        
        List<Double> similarities = new ArrayList<>();
        for (String doc : documents) {
            Map<Integer, Double> docVector = getSparseVector(doc);
            double similarity = 0.0;
            for (Map.Entry<Integer, Double> entry : queryVector.entrySet()) {
                int idx = entry.getKey();
                double queryScore = entry.getValue();
                Double docScore = docVector.get(idx);
                if (docScore != null) {
                    similarity += queryScore * docScore;
                }
            }
            similarities.add(similarity);
        }
        
        return similarities;
    }
    
    /**
     * 获取词汇表大小
     * @return 词汇表大小
     */
    public int getVocabularySize() {
        return vocab.size();
    }
    
    /**
     * 获取文档数量
     * @return 文档数量
     */
    public int getTotalDocs() {
        return totalDocs;
    }
    
    /**
     * 获取平均文档长度
     * @return 平均文档长度
     */
    public double getAvgDocLen() {
        return avgDocLen;
    }
    
    /**
     * 重置计算器状态
     */
    public void reset() {
        vocab.clear();
        vocabCounter = 0;
        docFreq.clear();
        totalDocs = 0;
        avgDocLen = 0.0;
    }
}
