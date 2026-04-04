package seislearner.service;

import seislearner.model.request.CreateAgentRequest;
import seislearner.model.request.UpdateAgentRequest;
import seislearner.model.response.CreateAgentResponse;
import seislearner.model.response.GetAgentsResponse;

public interface AgentFacadeService {
    GetAgentsResponse getAgents();

    CreateAgentResponse createAgent(CreateAgentRequest request);

    void deleteAgent(String agentId);

    void updateAgent(String agentId, UpdateAgentRequest request);
}
