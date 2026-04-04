package seislearner.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import seislearner.model.entity.ChunkBgeM3;

import java.util.List;
import java.util.Map;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity seislearner.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int updateById(ChunkBgeM3 chunkBgeM3);

    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    /**
     * 基于 PostgreSQL tsvector 的全文检索（稀疏检索/BM25）
     * 使用 to_tsvector('simple', content) + tsquery (OR 格式)
     * #{tsquery} 由 Java 端构造为 OR 格式，例如 '地震 | 波速 | 运动学'
     *
     * @param kbId    知识库ID
     * @param tsquery 已构造好的 tsquery 字符串（OR 格式，单引号包围每个词）
     * @param limit   最大返回条数
     * @return 包含 chunkId, docId, content, rank 的 Map 列表，按排名降序
     */
    List<Map<String, Object>> fullTextSearch(
            @Param("kbId") String kbId,
            @Param("tsquery") String tsquery,
            @Param("limit") int limit
    );

    /**
     * 根据文档ID删除所有关联的chunk
     */
    int deleteByDocId(@Param("docId") String docId);

    /**
     * 根据知识库ID删除所有关联的chunk
     */
    int deleteByKbId(@Param("kbId") String kbId);
}
