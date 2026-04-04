package seislearner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import seislearner.converter.DocumentConverter;
import seislearner.converter.KnowledgeBaseConverter;
import seislearner.exception.BizException;
import seislearner.mapper.ChunkBgeM3Mapper;
import seislearner.mapper.DocumentMapper;
import seislearner.mapper.KnowledgeBaseMapper;
import seislearner.model.dto.DocumentDTO;
import seislearner.model.dto.KnowledgeBaseDTO;
import seislearner.model.entity.Document;
import seislearner.model.entity.KnowledgeBase;
import seislearner.model.request.CreateKnowledgeBaseRequest;
import seislearner.model.request.UpdateKnowledgeBaseRequest;
import seislearner.model.response.CreateKnowledgeBaseResponse;
import seislearner.model.response.GetKnowledgeBasesResponse;
import seislearner.model.vo.KnowledgeBaseVO;
import seislearner.service.DocumentStorageService;
import seislearner.service.KnowledgeBaseFacadeService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class KnowledgeBaseFacadeServiceImpl implements KnowledgeBaseFacadeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final DocumentMapper documentMapper;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final DocumentStorageService documentStorageService;
    private final DocumentConverter documentConverter;

    @Override
    public GetKnowledgeBasesResponse getKnowledgeBases() {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectAll();
        List<KnowledgeBaseVO> result = new ArrayList<>();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            try {
                KnowledgeBaseVO vo = knowledgeBaseConverter.toVO(knowledgeBase);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetKnowledgeBasesResponse.builder()
                .knowledgeBases(result.toArray(new KnowledgeBaseVO[0]))
                .build();
    }

    @Override
    public CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        try {
            // 将 CreateKnowledgeBaseRequest 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(request);

            // 将 KnowledgeBaseDTO 转换为 KnowledgeBase 实体
            KnowledgeBase knowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            knowledgeBase.setCreatedAt(now);
            knowledgeBase.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = knowledgeBaseMapper.insert(knowledgeBase);
            if (result <= 0) {
                throw new BizException("创建知识库失败");
            }

            // 返回生成的 knowledgeBaseId
            return CreateKnowledgeBaseResponse.builder()
                    .knowledgeBaseId(knowledgeBase.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建知识库时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteKnowledgeBase(String knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null) {
            throw new BizException("知识库不存在: " + knowledgeBaseId);
        }

        log.info("开始删除知识库: knowledgeBaseId={}, name={}", knowledgeBaseId, knowledgeBase.getName());

        // 1. 查找该知识库下的所有文档
        List<Document> documents;
        try {
            documents = documentMapper.selectByKbId(knowledgeBaseId);
        } catch (Exception e) {
            log.warn("查询知识库文档失败，继续尝试删除: kbId={}, error={}", knowledgeBaseId, e.getMessage());
            documents = List.of();
        }

        // 2. 逐个删除文档的chunks和物理文件
        int totalChunksDeleted = 0;
        for (Document doc : documents) {
            // 2a. 删除该文档的所有chunks
            try {
                int deletedChunks = chunkBgeM3Mapper.deleteByDocId(doc.getId());
                totalChunksDeleted += deletedChunks;
            } catch (Exception e) {
                log.warn("删除文档chunks失败: docId={}, error={}", doc.getId(), e.getMessage());
            }

            // 2b. 删除物理文件
            try {
                DocumentDTO documentDTO = documentConverter.toDTO(doc);
                if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                    documentStorageService.deleteFile(documentDTO.getMetadata().getFilePath());
                }
            } catch (Exception e) {
                log.warn("删除文档物理文件失败: docId={}, error={}", doc.getId(), e.getMessage());
            }
        }
        log.info("知识库文档清理完成: kbId={}, 文档数={}, chunks删除数={}", 
                knowledgeBaseId, documents.size(), totalChunksDeleted);

        // 3. 删除所有文档记录
        try {
            int deletedDocs = documentMapper.deleteByKbId(knowledgeBaseId);
            log.info("删除知识库文档记录: kbId={}, 删除{}条", knowledgeBaseId, deletedDocs);
        } catch (Exception e) {
            log.warn("删除文档记录失败，继续删除知识库: kbId={}, error={}", knowledgeBaseId, e.getMessage());
        }

        // 4. 删除知识库记录
        int result = knowledgeBaseMapper.deleteById(knowledgeBaseId);
        if (result <= 0) {
            throw new BizException("删除知识库失败");
        }
        log.info("知识库删除完成: knowledgeBaseId={}", knowledgeBaseId);
    }

    @Override
    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        try {
            // 查询现有的知识库
            KnowledgeBase existingKnowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
            if (existingKnowledgeBase == null) {
                throw new BizException("知识库不存在: " + knowledgeBaseId);
            }

            // 将现有 KnowledgeBase 转换为 KnowledgeBaseDTO
            KnowledgeBaseDTO knowledgeBaseDTO = knowledgeBaseConverter.toDTO(existingKnowledgeBase);

            // 使用 UpdateKnowledgeBaseRequest 更新 KnowledgeBaseDTO
            knowledgeBaseConverter.updateDTOFromRequest(knowledgeBaseDTO, request);

            // 将更新后的 KnowledgeBaseDTO 转换为 KnowledgeBase 实体
            KnowledgeBase updatedKnowledgeBase = knowledgeBaseConverter.toEntity(knowledgeBaseDTO);

            // 保留原有 ID 和创建时间
            updatedKnowledgeBase.setId(existingKnowledgeBase.getId());
            updatedKnowledgeBase.setCreatedAt(existingKnowledgeBase.getCreatedAt());
            updatedKnowledgeBase.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = knowledgeBaseMapper.updateById(updatedKnowledgeBase);
            if (result <= 0) {
                throw new BizException("更新知识库失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新知识库时发生序列化错误: " + e.getMessage());
        }
    }
}
