package seislearner.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 多模态文档解析服务接口
 * 支持PDF、图像、Markdown等格式，通过MinerU服务解析
 */
public interface MultimodalParserService {
    /**
     * 解析多模态文档文件，返回Markdown格式内容
     *
     * @param file 上传的文件（PDF、图像、Markdown等）
     * @return 解析后的Markdown内容
     */
    String parseDocument(MultipartFile file);
    
    /**
     * 解析多模态文档文件（通过文件路径）
     *
     * @param filePath 文件路径
     * @return 解析后的Markdown内容
     */
    String parseDocument(String filePath);
    
    /**
     * 批量解析多个文档文件
     *
     * @param files 文件列表
     * @return 每个文件的解析结果列表
     */
    List<DocumentParseResult> parseDocuments(List<MultipartFile> files);
    
    /**
     * 文档解析结果数据类
     */
    @Data
    @AllArgsConstructor
    @ToString
    class DocumentParseResult {
        private String filename;
        private String markdownContent;
        private boolean success;
        private String errorMessage;
    }
}
