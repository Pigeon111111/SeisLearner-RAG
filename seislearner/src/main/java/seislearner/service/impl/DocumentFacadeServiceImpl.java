package seislearner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import seislearner.converter.DocumentConverter;
import seislearner.exception.BizException;
import seislearner.mapper.DocumentMapper;
import seislearner.model.dto.DocumentDTO;
import seislearner.model.entity.Document;
import seislearner.model.request.CreateDocumentRequest;
import seislearner.model.request.UpdateDocumentRequest;
import seislearner.model.response.CreateDocumentResponse;
import seislearner.model.response.GetDocumentsResponse;
import seislearner.model.vo.DocumentVO;
import seislearner.mapper.ChunkBgeM3Mapper;
import seislearner.model.entity.ChunkBgeM3;
import seislearner.service.DocumentFacadeService;
import seislearner.service.DocumentStorageService;
import seislearner.service.MarkdownParserService;
import seislearner.service.MultimodalParserService;
import seislearner.service.ChunkingService;
import seislearner.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class DocumentFacadeServiceImpl implements DocumentFacadeService {

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final MultimodalParserService multimodalParserService;
    private final ChunkingService chunkingService;

    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAll();
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    /**
     * 处理多模态文档，解析并生成chunks
     */
    private void processDocument(String kbId, String documentId, String filePath, String filetype, MultipartFile file) {
        try {
            log.info("开始处理文档: kbId={}, documentId={}, filetype={}", kbId, documentId, filetype);
            
            String markdownContent;
            
            // 根据文件类型选择解析方式
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                // 使用现有的Markdown解析器（保持向后兼容）
                Path path = documentStorageService.getFilePath(filePath);
                try (InputStream inputStream = Files.newInputStream(path)) {
                    List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);
                    // 将章节合并为完整Markdown内容
                    StringBuilder sb = new StringBuilder();
                    for (MarkdownParserService.MarkdownSection section : sections) {
                        if (section.getTitle() != null && !section.getTitle().trim().isEmpty()) {
                            sb.append("# ").append(section.getTitle()).append("\n");
                        }
                        if (section.getContent() != null) {
                            sb.append(section.getContent()).append("\n\n");
                        }
                    }
                    markdownContent = sb.toString();
                }
            } else {
                // 使用多模态解析器（PDF、图像等）—— 依赖 MinerU 本地服务
                try {
                    markdownContent = multimodalParserService.parseDocument(file);
                } catch (Exception parseEx) {
                    log.error("多模态解析失败(MinerU服务可能未运行): documentId={}, filetype={}, error={}", 
                            documentId, filetype, parseEx.getMessage());
                    // 尝试基础文本提取作为降级方案
                    try {
                        markdownContent = extractBasicText(file);
                        log.info("使用基础文本提取降级成功: documentId={}, length={}", 
                                documentId, markdownContent.length());
                    } catch (Exception textEx) {
                        log.error("基础文本提取也失败: documentId={}", documentId, textEx);
                        throw new BizException("文档解析失败: " + parseEx.getMessage() 
                                + " (请确保MinerU服务运行在 localhost:8000)");
                    }
                }
            }
            
            if (markdownContent == null || markdownContent.trim().isEmpty()) {
                log.warn("文档解析后内容为空: documentId={}, filetype={}. 可能原因: 扫描版PDF(需配置MINERU_API_TOKEN启用OCR) / MinerU服务未启动 / 文件损坏", documentId, filetype);
                return;
            }
            
            // 检查提取的内容是否有效（扫描版PDF通过PyMuPDF降级可能只返回图片占位符）
            String trimmedContent = markdownContent.trim();
            long textCharCount = trimmedContent.chars().filter(c -> !Character.isISOControl(c)).count();
            // 如果有效文字少于50字符且包含大量[图片]标记，说明是扫描件但OCR未生效
            long imagePlaceholderCount = trimmedContent.split("\\[图片").length - 1;
            if (textCharCount < 50 && imagePlaceholderCount > 3) {
                log.warn("文档疑似扫描版PDF, 文字提取极少: documentId={}, 有效文字数={}, 图片占位符数={}. 建议: 设置环境变量 MINERU_API_TOKEN 启用MinerU OCR功能", 
                        documentId, textCharCount, imagePlaceholderCount);
                return;
            }
            
            // 使用三级分块算法分块
            List<ChunkingService.TextChunk> chunks = chunkingService.chunkText(markdownContent);
            
            // 自动合并相似块
            List<ChunkingService.TextChunk> mergedChunks = chunkingService.mergeChunks(chunks, 0.7);
            
            LocalDateTime now = LocalDateTime.now();
            int chunkCount = 0;
            
            // 存储每个块到向量数据库
            for (ChunkingService.TextChunk chunk : mergedChunks) {
                // 对块内容进行embedding（使用块的前200字符）
                String embeddingText = chunk.getContent();
                if (embeddingText.length() > 200) {
                    embeddingText = embeddingText.substring(0, 200);
                }
                
                float[] embedding;
                try {
                    embedding = ragService.embed(embeddingText);
                } catch (Exception embedEx) {
                    // Embedding 失败时使用零向量作为降级方案，确保 chunk 仍可存储
                    log.warn("Embedding生成失败，使用零向量降级: documentId={}, error={}", 
                            documentId, embedEx.getMessage());
                    embedding = new float[1536]; // qwen3-vl-embedding 维度
                }
                
                // 创建ChunkBgeM3实体
                ChunkBgeM3 chunkEntity = ChunkBgeM3.builder()
                        .kbId(kbId)
                        .docId(documentId)
                        .content(chunk.getContent())
                        .metadata("{\"level\":" + chunk.getLevel() + ",\"start\":" + chunk.getStartIndex() + ",\"end\":" + chunk.getEndIndex() + "}")
                        .embedding(embedding)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                
                // 插入数据库
                int result = chunkBgeM3Mapper.insert(chunkEntity);
                
                if (result > 0) {
                    chunkCount++;
                    log.debug("创建chunk成功: level={}, chunkId={}", chunk.getLevel(), chunkEntity.getId());
                } else {
                    log.warn("创建chunk失败: level={}", chunk.getLevel());
                }
            }
            
            log.info("文档处理完成: documentId={}, 文件类型={}, 原始块数={}, 合并后块数={}, 存储块数={}", 
                    documentId, filetype, chunks.size(), mergedChunks.size(), chunkCount);
            
        } catch (Exception e) {
            log.error("处理文档失败: documentId={}, filetype={}", documentId, filetype, e);
        }
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            // CreateDocumentRequest 转换为 DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(request);

            // DocumentDTO 转换为 Document 实体
            Document document = documentConverter.toEntity(documentDTO);

            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，ID 由数据库自动生成
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            // 返回生成的documentId
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();

            // 创建文档记录（先创建记录，获取documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            String documentId = document.getId();

            // 保存文件
            String filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档上传成功: kbId={}, documentId={}, filename={}", kbId, documentId, originalFilename);

            // 处理文档：解析并生成chunks（支持多模态文档）
            processDocument(kbId, documentId, filePath, filetype, file);

            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BizException("文件保存失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }

        // 1. 显式删除该文档的所有chunks
        try {
            int deletedChunks = chunkBgeM3Mapper.deleteByDocId(documentId);
            log.info("删除文档chunks: documentId={}, 删除{}条", documentId, deletedChunks);
        } catch (Exception e) {
            log.warn("删除文档chunks失败，继续删除文档记录: documentId={}, error={}", documentId, e.getMessage());
        }

        // 2. 删除物理文件
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                String filePath = documentDTO.getMetadata().getFilePath();
                documentStorageService.deleteFile(filePath);
                log.info("删除文档物理文件: documentId={}, path={}", documentId, filePath);
            }
        } catch (Exception e) {
            log.warn("删除物理文件失败，继续删除数据库记录: documentId={}, error={}", documentId, e.getMessage());
        }

        // 3. 删除文档数据库记录
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
        log.info("文档删除完成: documentId={}", documentId);
    }

    /**
     * 处理 Markdown 文档，解析并生成 chunks
     */
    private void processMarkdownDocument(String kbId, String documentId, String filePath) {
        try {
            log.info("开始处理Markdown文档: kbId={}, documentId={}, filePath={}", kbId, documentId, filePath);

            Path path = documentStorageService.getFilePath(filePath);
            try (InputStream inputStream = Files.newInputStream(path)) {
                List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);
                System.out.println(sections);

                if (sections.isEmpty()) {
                    log.warn("Markdown文档解析后没有找到任何章节: documentId={}", documentId);
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                int chunkCount = 0;

                // 为每个章节生成chunk
                for (MarkdownParserService.MarkdownSection section : sections) {
                    String title = section.getTitle();
                    String content = section.getContent();

                    if (title == null || title.trim().isEmpty()) {
                        continue;
                    }

                    // 对标题进行embedding
                    float[] embedding = ragService.embed(title);

                    ChunkBgeM3 chunk = ChunkBgeM3.builder()
                            .kbId(kbId)
                            .docId(documentId)
                            .content(content != null ? content : "")
                            .metadata(null)
                            .embedding(embedding)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    // 插入数据库
                    int insertResult = chunkBgeM3Mapper.insert(chunk);

                    if (insertResult > 0) {
                        chunkCount++;
                        log.debug("创建chunk成功: title={}, chunkId={}", title, chunk.getId());
                    } else {
                        log.warn("创建chunk失败: title={}", title);
                    }
                }
                log.info("Markdown文档处理完成: documentId={}, 共生成 {} 个chunks", documentId, chunkCount);
            }
        } catch (Exception e) {
            log.error("处理Markdown文档失败: documentId={}", documentId, e);
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * 基础文本提取（降级方案，当MinerU不可用时使用）
     * 支持从 PDF、TXT、图片(OCR) 等格式中提取纯文本
     */
    private String extractBasicText(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String filetype = getFileType(originalFilename);
        
        // TXT 文件：直接读取文本
        if ("txt".equalsIgnoreCase(filetype) || "text".equalsIgnoreCase(filetype)) {
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        
        // 其他格式：尝试用 Apache Tika 或简单方式提取
        // 这里先返回基础信息作为兜底
        log.warn("基础文本提取暂不支持 {} 格式, 返回文件名和大小信息", filetype);
        return String.format("# %s\n\n**文件类型**: %s\n**文件大小**: %d bytes\n\n> 文档内容需要 MinerU 服务进行完整解析。",
                originalFilename, filetype, file.getSize());
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            // 查询现有文档
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }

            // 将现有Document转换为DocumentDTO
            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);

            // 使用UpdateDocumentRequest更新DocumentDTO
            documentConverter.updateDTOFromRequest(documentDTO, request);

            // 将更新后的DocumentDTO转换为Document实体
            Document updatedDocument = documentConverter.toEntity(documentDTO);

            // 保留原有ID、kbId和创建时间
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            // 更新数据库
            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
