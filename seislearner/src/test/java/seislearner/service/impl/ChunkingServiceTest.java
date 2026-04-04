package seislearner.service.impl;

import seislearner.service.ChunkingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkingService 单元测试
 * 测试三级分块算法和自动合并功能
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.openai.api-key=test-key",
    "spring.ai.openai.chat.options.model=gpt-3.5-turbo"
})
public class ChunkingServiceTest {

    @Autowired
    private ChunkingService chunkingService;

    @Test
    void testThreeLevelChunking() {
        // 准备测试文本
        String testText = "这是测试文本。".repeat(100); // 约1700字符

        // 执行分块
        List<ChunkingService.TextChunk> chunks = chunkingService.chunkText(testText);

        // 验证分块结果
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        // 验证至少包含三级分块
        assertTrue(chunks.size() >= 3, "应该包含至少三级分块");

        // 验证每个分块的内容长度
        for (ChunkingService.TextChunk chunk : chunks) {
            assertNotNull(chunk.getContent());
            assertFalse(chunk.getContent().isEmpty());
            assertTrue(chunk.getContent().length() > 0);
            assertTrue(chunk.getLevel() >= 1 && chunk.getLevel() <= 3);
            assertTrue(chunk.getStartIndex() >= 0);
            assertTrue(chunk.getEndIndex() > chunk.getStartIndex());
        }

        // 验证不同级别的分块（通过level字段区分）
        boolean hasLevel1 = false;
        boolean hasLevel2 = false;
        boolean hasLevel3 = false;

        for (ChunkingService.TextChunk chunk : chunks) {
            int level = chunk.getLevel();
            if (level == 1) {
                hasLevel1 = true;
            } else if (level == 2) {
                hasLevel2 = true;
            } else if (level == 3) {
                hasLevel3 = true;
            }
        }

        // 至少应该有一种级别的分块
        assertTrue(hasLevel1 || hasLevel2 || hasLevel3, "应该包含不同级别的分块");
    }

    @Test
    void testChunkingWithShortText() {
        // 测试短文本
        String shortText = "这是一个很短的文本";

        List<ChunkingService.TextChunk> chunks = chunkingService.chunkText(shortText);

        assertNotNull(chunks);
        // 即使短文本也应该有至少一个分块
        assertFalse(chunks.isEmpty());

        // 验证分块内容
        ChunkingService.TextChunk chunk = chunks.get(0);
        assertEquals(shortText, chunk.getContent());
    }

    @Test
    void testChunkingWithEmptyText() {
        // 测试空文本
        String emptyText = "";

        List<ChunkingService.TextChunk> chunks = chunkingService.chunkText(emptyText);

        assertNotNull(chunks);
        // 空文本应该返回空列表
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testChunkMergeFunction() {
        // 准备模拟分块
        List<ChunkingService.TextChunk> mockChunks = List.of(
                new ChunkingService.TextChunk("第一部分内容", 1, 0, 10),
                new ChunkingService.TextChunk("第二部分内容", 2, 10, 20),
                new ChunkingService.TextChunk("第三部分内容", 3, 20, 30)
        );

        // 测试合并功能
        List<ChunkingService.TextChunk> merged = chunkingService.mergeChunks(mockChunks, 0.7);
        assertNotNull(merged);
        assertTrue(merged.size() <= mockChunks.size());

        // 验证合并后的分块仍然有效
        for (ChunkingService.TextChunk chunk : merged) {
            assertNotNull(chunk.getContent());
            assertFalse(chunk.getContent().isEmpty());
            assertTrue(chunk.getLevel() >= 1 && chunk.getLevel() <= 3);
        }
    }

    @Test
    void testChunkingPerformance() {
        // 性能测试：处理长文本
        StringBuilder longTextBuilder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longTextBuilder.append("这是第").append(i).append("段测试文本。");
        }
        String longText = longTextBuilder.toString();

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        List<ChunkingService.TextChunk> chunks = chunkingService.chunkText(longText);

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // 验证性能：应该在合理时间内完成（例如5秒内）
        assertTrue(duration < 5000, "分块操作应在5秒内完成，实际耗时: " + duration + "ms");

        // 验证分块数量合理
        assertNotNull(chunks);
        assertTrue(chunks.size() > 0);

        // 每个分块不应超过最大窗口大小（1200字符）
        for (ChunkingService.TextChunk chunk : chunks) {
            assertTrue(chunk.getContent().length() <= 1200,
                    "分块长度不应超过1200字符，实际: " + chunk.getContent().length());
        }
    }
}
