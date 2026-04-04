package seislearner.service;

import seislearner.model.request.CreateDocumentRequest;
import seislearner.model.request.UpdateDocumentRequest;
import seislearner.model.response.CreateDocumentResponse;
import seislearner.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFacadeService {
    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
