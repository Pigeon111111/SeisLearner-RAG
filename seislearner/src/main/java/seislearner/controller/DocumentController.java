package seislearner.controller;

import seislearner.model.common.ApiResponse;
import seislearner.model.request.CreateDocumentRequest;
import seislearner.model.request.UpdateDocumentRequest;
import seislearner.model.response.CreateDocumentResponse;
import seislearner.model.response.GetDocumentsResponse;
import seislearner.service.DocumentFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class DocumentController {

    private final DocumentFacadeService documentFacadeService;

    // 查询所有文?
    @GetMapping("/documents")
    public ApiResponse<GetDocumentsResponse> getDocuments() {
        return ApiResponse.success(documentFacadeService.getDocuments());
    }

    // 根据 kbId 查询文档
    @GetMapping("/documents/kb/{kbId}")
    public ApiResponse<GetDocumentsResponse> getDocumentsByKbId(@PathVariable String kbId) {
        return ApiResponse.success(documentFacadeService.getDocumentsByKbId(kbId));
    }

    // 创建文档（仅创建记录，不上传文件?
    @PostMapping("/documents")
    public ApiResponse<CreateDocumentResponse> createDocument(@RequestBody CreateDocumentRequest request) {
        return ApiResponse.success(documentFacadeService.createDocument(request));
    }

    // 上传文档（上传文件并创建记录?
    @PostMapping("/documents/upload")
    public ApiResponse<CreateDocumentResponse> uploadDocument(
            @RequestParam("kbId") String kbId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(documentFacadeService.uploadDocument(kbId, file));
    }

    // 删除文档
    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Void> deleteDocument(@PathVariable String documentId) {
        documentFacadeService.deleteDocument(documentId);
        return ApiResponse.success();
    }

    // 更新文档
    @PatchMapping("/documents/{documentId}")
    public ApiResponse<Void> updateDocument(@PathVariable String documentId, @RequestBody UpdateDocumentRequest request) {
        documentFacadeService.updateDocument(documentId, request);
        return ApiResponse.success();
    }
}
