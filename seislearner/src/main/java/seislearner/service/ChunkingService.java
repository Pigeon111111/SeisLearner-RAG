package seislearner.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * 文本分块服务接口
 * 实现三级分块算法和自动合并逻辑
 */
public interface ChunkingService {
    /**
     * 对文本进行三级分块
     *
     * @param text 输入文本
     * @return 分块结果列表
     */
    List<TextChunk> chunkText(String text);
    
    /**
     * 对文本进行三级分块，可自定义参数
     *
     * @param text 输入文本
     * @param level1Window 第一级窗口大小（字符数）
     * @param level1Overlap 第一级重叠大小（字符数）
     * @param level2Window 第二级窗口大小
     * @param level2Overlap 第二级重叠大小
     * @param level3Window 第三级窗口大小
     * @param level3Overlap 第三级重叠大小
     * @return 分块结果列表
     */
    List<TextChunk> chunkText(String text, 
                             int level1Window, int level1Overlap,
                             int level2Window, int level2Overlap,
                             int level3Window, int level3Overlap);
    
    /**
     * 对文本进行单级分块
     *
     * @param text 输入文本
     * @param windowSize 窗口大小
     * @param overlapSize 重叠大小
     * @return 分块结果列表
     */
    List<String> chunkTextSimple(String text, int windowSize, int overlapSize);
    
    /**
     * 自动合并相似或连续的块
     *
     * @param chunks 分块列表
     * @param similarityThreshold 相似度阈值（0-1）
     * @return 合并后的分块列表
     */
    List<TextChunk> mergeChunks(List<TextChunk> chunks, double similarityThreshold);
    
    /**
     * 文本块数据类
     */
    @Data
    @AllArgsConstructor
    @ToString
    class TextChunk {
        private String content;
        private int level; // 分块级别: 1, 2, 3
        private int startIndex; // 在原文中的起始位置
        private int endIndex; // 在原文中的结束位置
        private String metadata; // 可选的元数据（JSON字符串）
        
        public TextChunk(String content, int level, int startIndex, int endIndex) {
            this.content = content;
            this.level = level;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.metadata = null;
        }
    }
}
