package seislearner.service.impl;

import seislearner.service.MultimodalParserService;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class MultimodalParserServiceImpl implements MultimodalParserService {
    
    private final RestTemplate restTemplate;
    private final String mineruApiBaseUrl;
    
    public MultimodalParserServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);   // 连接超时 30秒
        factory.setReadTimeout(300_000);     // 读取超时 5分钟（MinerU解析大PDF需要较长时间）
        this.restTemplate = new RestTemplate(factory);
        // 从配置读取，暂时硬编码
        this.mineruApiBaseUrl = "http://localhost:8000";
    }
    
    @Override
    public String parseDocument(MultipartFile file) {
        Path tempFile = null;
        Path tempPdfFile = null;
        
        try {
            // 获取原始文件名和扩展名
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            
            // 创建临时文件，保留原始扩展名以便识别文件类型
            String prefix = "mineru_";
            String suffix = extension.isEmpty() ? ".tmp" : extension;
            tempFile = Files.createTempFile(prefix, suffix);
            file.transferTo(tempFile.toFile());
            
            // 如果是PPT文件，需要处理转换后的PDF临时文件
            if (isPptFile(tempFile.toString())) {
                // 先尝试转换PPT为PDF
                String pdfPath = convertPptToPdf(tempFile.toString());
                if (pdfPath != null && Files.exists(Path.of(pdfPath))) {
                    tempPdfFile = Path.of(pdfPath);
                    // 使用转换后的PDF文件进行解析
                    return parseDocument(pdfPath);
                } else {
                    log.warn("PPT转换失败，尝试直接解析原文件");
                }
            }
            
            // 对于非PPT文件或转换失败的情况，直接解析原文件
            return parseDocument(tempFile.toString());
            
        } catch (IOException e) {
            log.error("文件处理失败", e);
            throw new RuntimeException("文件处理失败: " + e.getMessage(), e);
        } finally {
            // 清理临时文件
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
                if (tempPdfFile != null) {
                    Files.deleteIfExists(tempPdfFile);
                }
            } catch (IOException e) {
                log.warn("临时文件清理失败", e);
            }
        }
    }
    
    @Override
    public String parseDocument(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new IllegalArgumentException("文件不存在: " + filePath);
            }
            
            // 检查是否为PPT文件，如果是则先转换为PDF
            String finalFilePath = filePath;
            if (isPptFile(filePath)) {
                log.info("检测到PPT文件，开始转换为PDF: {}", filePath);
                String pdfPath = convertPptToPdf(filePath);
                if (pdfPath != null && new File(pdfPath).exists()) {
                    finalFilePath = pdfPath;
                    log.info("PPT转换为PDF成功: {} -> {}", filePath, pdfPath);
                } else {
                    log.warn("PPT转换失败，将尝试直接解析原文");
                }
            }
            
            // 调用MinerU API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(new File(finalFilePath)));
            body.add("lang_list", "ch,en");
            body.add("formula_enable", "true");
            body.add("table_enable", "true");
            body.add("return_md", "true");
            body.add("return_middle_json", "false");
            
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            
            String apiUrl = mineruApiBaseUrl + "/file_parse";
            log.debug("调用MinerU API: {}", apiUrl);
            
            ResponseEntity<MinerUApiResponse> response = restTemplate.postForEntity(
                apiUrl, requestEntity, MinerUApiResponse.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                MinerUApiResponse apiResponse = response.getBody();
                if ("success".equals(apiResponse.getStatus())) {
                    return apiResponse.getMarkdownContent();
                } else {
                    throw new RuntimeException("MinerU解析失败: " + apiResponse.getMessage());
                }
            } else {
                throw new RuntimeException("MinerU API调用失败: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("文档解析失败: filePath={}", filePath, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 判断文件是否为PPT格式
     */
    private boolean isPptFile(String filePath) {
        if (filePath == null) {
            return false;
        }
        String lowerPath = filePath.toLowerCase();
        return lowerPath.endsWith(".ppt") || lowerPath.endsWith(".pptx");
    }
    
    /**
     * 将PPT文件转换为PDF格式
     * @param pptPath PPT文件路径
     * @return 转换后的PDF文件路径，转换失败返回null
     */
    private String convertPptToPdf(String pptPath) {
        try {
            File pptFile = new File(pptPath);
            if (!pptFile.exists()) {
                log.error("PPT文件不存在: {}", pptPath);
                return null;
            }
            
            // 生成PDF文件路径（在同一目录下，相同文件名，扩展名改为.pdf）
            String pdfPath = pptPath.substring(0, pptPath.lastIndexOf('.')) + ".pdf";
            
            // 构建LibreOffice转换命令
            // Windows下LibreOffice通常安装?C:\Program Files\LibreOffice\program\soffice.exe
            String sofficePath = "soffice";
            
            // 尝试不同的路径
            String[] possiblePaths = {
                "soffice",
                "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
                "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe"
            };
            
            String command = null;
            for (String path : possiblePaths) {
                try {
                    // 检查命令是否存在
                    ProcessBuilder pb = new ProcessBuilder(path, "--version");
                    Process process = pb.start();
                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        sofficePath = path;
                        break;
                    }
                } catch (Exception e) {
                    // 路径不可用，继续尝试下一个
                    continue;
                }
            }
            
            // 构建转换命令
            String pdfDir = new File(pdfPath).getParent();
            String[] cmdArray = {
                sofficePath,
                "--headless",
                "--convert-to", "pdf",
                "--outdir", pdfDir,
                pptPath
            };
            
            log.info("执行PPT转换命令: {}", String.join(" ", cmdArray));
            
            ProcessBuilder processBuilder = new ProcessBuilder(cmdArray);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            // 读取输出
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("LibreOffice输出: {}", line);
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                File pdfFile = new File(pdfPath);
                if (pdfFile.exists() && pdfFile.length() > 0) {
                    log.info("PPT转换成功: {} -> {} (大小: {} 字节)", 
                            pptPath, pdfPath, pdfFile.length());
                    return pdfPath;
                } else {
                    log.error("PPT转换失败: PDF文件未生成或为空");
                    return null;
                }
            } else {
                log.error("PPT转换失败: LibreOffice退出代码: {}", exitCode);
                return null;
            }
        } catch (Exception e) {
            log.error("PPT转换异常: {}", pptPath, e);
            return null;
        }
    }
    
    @Override
    public List<DocumentParseResult> parseDocuments(List<MultipartFile> files) {
        List<DocumentParseResult> results = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                String markdownContent = parseDocument(file);
                results.add(new DocumentParseResult(
                    file.getOriginalFilename(),
                    markdownContent,
                    true,
                    null
                ));
            } catch (Exception e) {
                log.error("文件解析失败: filename={}", file.getOriginalFilename(), e);
                results.add(new DocumentParseResult(
                    file.getOriginalFilename(),
                    null,
                    false,
                    e.getMessage()
                ));
            }
        }
        return results;
    }
    
    /**
     * MinerU API响应数据结构
     */
    @Data
    @AllArgsConstructor
    private static class MinerUApiResponse {
        private String status;
        private String message;
        @JsonProperty("markdown_content")
        private String markdownContent;
        @JsonProperty("middle_json")
        private Object middleJson;
        private String error;
        
        public MinerUApiResponse() {
            // 默认构造函数用于反序列化
        }
    }
}