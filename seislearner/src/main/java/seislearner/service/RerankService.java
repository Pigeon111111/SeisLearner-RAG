package seislearner.service;

import java.util.List;

/**
 * Rerank 精排序服务接口
 * 使用阿里云 qwen3-vl-rerank 模型对检索结果进行精排
 */
public interface RerankService {

    /**
     * 对检索结果列表进行精排序
     *
     * @param query   用户查询
     * @param documents 待排序的文档内容列表
     * @param topK    返回前 K 个结果
     * @return 排序后的结果，包含原始索引和分数
     */
    List<RerankResult> rerank(String query, List<String> documents, int topK);

    /**
     * Rerank 结果
     */
    interface RerankResult {
        /** 原始文档内容 */
        String getContent();
        /** Rerank 分数 (0-1)，越高越相关 */
        double getScore();
        /** 在原始列表中的索引 */
        int getOriginalIndex();
    }
}
