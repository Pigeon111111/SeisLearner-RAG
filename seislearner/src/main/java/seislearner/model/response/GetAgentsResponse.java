package seislearner.model.response;

import seislearner.model.vo.AgentVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetAgentsResponse {
    private AgentVO[] agents;
}
