package seislearner.service.impl;

import seislearner.service.ChunkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class ChunkingServiceImpl implements ChunkingService {
    
    // 默认分块参数（三级分块）
    private static final int DEFAULT_LEVEL1_WINDOW = 1200;
    private static final int DEFAULT_LEVEL1_OVERLAP = 240;
    private static final int DEFAULT_LEVEL2_WINDOW = 600;
    private static final int DEFAULT_LEVEL2_OVERLAP = 120;
    private static final int DEFAULT_LEVEL3_WINDOW = 300;
    private static final int DEFAULT_LEVEL3_OVERLAP = 60;
    
    @Override
    public List<TextChunk> chunkText(String text) {
        return chunkText(text,
            DEFAULT_LEVEL1_WINDOW, DEFAULT_LEVEL1_OVERLAP,
            DEFAULT_LEVEL2_WINDOW, DEFAULT_LEVEL2_OVERLAP,
            DEFAULT_LEVEL3_WINDOW, DEFAULT_LEVEL3_OVERLAP);
    }
    
    @Override
    public List<TextChunk> chunkText(String text,
                                    int level1Window, int level1Overlap,
                                    int level2Window, int level2Overlap,
                                    int level3Window, int level3Overlap) {
        List<TextChunk> allChunks = new ArrayList<>();
        
        // 第一级分块
        List<TextChunk> level1Chunks = chunkTextWithLevel(text, level1Window, level1Overlap, 1);
        allChunks.addAll(level1Chunks);
        
        // 第二级分块
        List<TextChunk> level2Chunks = chunkTextWithLevel(text, level2Window, level2Overlap, 2);
        allChunks.addAll(level2Chunks);
        
        // 第三级分块
        List<TextChunk> level3Chunks = chunkTextWithLevel(text, level3Window, level3Overlap, 3);
        allChunks.addAll(level3Chunks);
        
        // 按起始位置排序
        allChunks.sort((a, b) -> Integer.compare(a.getStartIndex(), b.getStartIndex()));
        
        return allChunks;
    }
    
    @Override
    public List<String> chunkTextSimple(String text, int windowSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        
        int textLength = text.length();
        int start = 0;
        
        while (start < textLength) {
            int end = Math.min(start + windowSize, textLength);
            String chunk = text.substring(start, end);
            chunks.add(chunk);
            
            // 移动窗口，考虑重叠
            start += (windowSize - overlapSize);
            
            // 如果剩余文本小于窗口大小但大于重叠大小，调整结束位置
            if (start >= textLength) {
                break;
            }
        }
        
        return chunks;
    }
    
    @Override
    public List<TextChunk> mergeChunks(List<TextChunk> chunks, double similarityThreshold) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<TextChunk> mergedChunks = new ArrayList<>();
        TextChunk currentChunk = chunks.get(0);
        
        for (int i = 1; i < chunks.size(); i++) {
            TextChunk nextChunk = chunks.get(i);
            
            // 检查是否连续或相似
            boolean shouldMerge = false;
            
            // 1. 检查连续性：如果下一个块的起始位置在当前块的结束位置附近
            if (nextChunk.getStartIndex() <= currentChunk.getEndIndex() + 50) {
                shouldMerge = true;
            }
            // 2. 检查相似性（简化版：内容重叠度检查）
            else {
                double similarity = calculateContentSimilarity(
                    currentChunk.getContent(), nextChunk.getContent());
                if (similarity >= similarityThreshold) {
                    shouldMerge = true;
                }
            }
            
            if (shouldMerge) {
                // 合并两个块
                String mergedContent = currentChunk.getContent() + "\n" + nextChunk.getContent();
                int mergedLevel = Math.min(currentChunk.getLevel(), nextChunk.getLevel());
                TextChunk merged = new TextChunk(
                    mergedContent,
                    mergedLevel,
                    currentChunk.getStartIndex(),
                    nextChunk.getEndIndex()
                );
                currentChunk = merged;
            } else {
                // 保存当前块，开始新的块
                mergedChunks.add(currentChunk);
                currentChunk = nextChunk;
            }
        }
        
        // 添加最后一个块
        mergedChunks.add(currentChunk);
        
        return mergedChunks;
    }
    
    /**
     * 按指定级别分块
     */
    private List<TextChunk> chunkTextWithLevel(String text, int windowSize, int overlapSize, int level) {
        List<TextChunk> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        
        int textLength = text.length();
        int start = 0;
        
        while (start < textLength) {
            int end = Math.min(start + windowSize, textLength);
            String chunkContent = text.substring(start, end);
            
            TextChunk chunk = new TextChunk(chunkContent, level, start, end);
            chunks.add(chunk);
            
            // 移动窗口，考虑重叠
            start += (windowSize - overlapSize);
            
            // 如果剩余文本小于窗口大小但大于重叠大小，调整结束位置
            if (start >= textLength) {
                break;
            }
        }
        
        log.debug("级别{}分块完成: 窗口大小={}, 重叠大小={}, 生成块数={}", 
                 level, windowSize, overlapSize, chunks.size());
        return chunks;
    }
    
    /**
     * 计算两个文本内容的相似度（简化版：基于重叠字符比例）
     */
    private double calculateContentSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null || text1.isEmpty() || text2.isEmpty()) {
            return 0.0;
        }
        
        // 使用简单的Jaccard相似度（基于字符集合）
        Set<Character> set1 = new HashSet<>();
        Set<Character> set2 = new HashSet<>();
        
        for (char c : text1.toCharArray()) {
            set1.add(c);
        }
        for (char c : text2.toCharArray()) {
            set2.add(c);
        }
        
        Set<Character> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<Character> union = new HashSet<>(set1);
        union.addAll(set2);
        
        if (union.isEmpty()) {
            return 0.0;
        }
        
        return (double) intersection.size() / union.size();
    }
}
