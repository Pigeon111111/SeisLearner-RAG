package seislearner.service;

import seislearner.model.request.CreateKnowledgeBaseRequest;
import seislearner.model.request.UpdateKnowledgeBaseRequest;
import seislearner.model.response.CreateKnowledgeBaseResponse;
import seislearner.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {
    GetKnowledgeBasesResponse getKnowledgeBases();

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}

