package seislearner.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * 图片代理控制器
 * 将前端的图片请求代理到 MinerU 服务，解决跨域和端口问题
 */
@RestController
@RequestMapping("/images")
@Slf4j
public class ImageProxyController {

    private final RestTemplate restTemplate;
    private final String mineruApiBaseUrl;

    public ImageProxyController() {
        this.restTemplate = new RestTemplate();
        this.mineruApiBaseUrl = "http://localhost:8000";
    }

    /**
     * 代理 MinerU 图片请求
     * 前端请求: /images/{docId}/{filename}
     * 转发到: http://localhost:8000/images/{docId}/{filename}
     */
    @GetMapping("/{docId}/{filename}")
    public ResponseEntity<byte[]> proxyImage(
            @PathVariable String docId,
            @PathVariable String filename) {
        
        try {
            String imageUrl = String.format("%s/images/%s/%s", mineruApiBaseUrl, docId, filename);
            log.debug("代理图片请求: {}", imageUrl);
            
            ResponseEntity<byte[]> response = restTemplate.getForEntity(imageUrl, byte[].class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                HttpHeaders headers = new HttpHeaders();
                
                // 根据文件扩展名设置 Content-Type
                String contentType = getContentType(filename);
                headers.setContentType(MediaType.parseMediaType(contentType));
                
                // 设置缓存
                headers.setCacheControl("max-age=86400");
                
                return new ResponseEntity<>(response.getBody(), headers, HttpStatus.OK);
            } else {
                log.warn("图片获取失败: status={}", response.getStatusCode());
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("代理图片请求失败: docId={}, filename={}", docId, filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 根据文件扩展名获取 Content-Type
     */
    private String getContentType(String filename) {
        String ext = filename.toLowerCase();
        if (ext.endsWith(".png")) {
            return "image/png";
        } else if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (ext.endsWith(".gif")) {
            return "image/gif";
        } else if (ext.endsWith(".webp")) {
            return "image/webp";
        } else if (ext.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (ext.endsWith(".bmp")) {
            return "image/bmp";
        } else if (ext.endsWith(".tiff") || ext.endsWith(".tif")) {
            return "image/tiff";
        }
        return "application/octet-stream";
    }
}
