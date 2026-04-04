package seislearner.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import seislearner.converter.AgentConverter;
import seislearner.exception.BizException;
import seislearner.mapper.AgentMapper;
import seislearner.model.dto.AgentDTO;
import seislearner.model.entity.Agent;
import seislearner.model.request.CreateAgentRequest;
import seislearner.model.request.UpdateAgentRequest;
import seislearner.model.response.CreateAgentResponse;
import seislearner.model.response.GetAgentsResponse;
import seislearner.model.vo.AgentVO;
import seislearner.service.AgentFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Service
@AllArgsConstructor
public class AgentFacadeServiceImpl implements AgentFacadeService {

    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;

    @Override
    public GetAgentsResponse getAgents() {
        List<Agent> agents = agentMapper.selectAll();
        List<AgentVO> result = new ArrayList<>();
        for (Agent agent : agents) {
            try {
                AgentVO vo = agentConverter.toVO(agent);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetAgentsResponse.builder()
                .agents(result.toArray(new AgentVO[0]))
                .build();
    }

    @Override
    public CreateAgentResponse createAgent(CreateAgentRequest request) {
        try {
            // ?CreateAgentRequest 转换?AgentDTO
            AgentDTO agentDTO = agentConverter.toDTO(request);
            
            // ?AgentDTO 转换?Agent 实体
            Agent agent = agentConverter.toEntity(agentDTO);
            
            // 设置创建时间和更新时?
            LocalDateTime now = LocalDateTime.now();
            agent.setCreatedAt(now);
            agent.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = agentMapper.insert(agent);
            if (result <= 0) {
                throw new BizException("创建 agent 失败");
            }
            
            // 返回生成?agentId
            return CreateAgentResponse.builder()
                    .agentId(agent.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建 agent 时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存? " + agentId);
        }
        
        int result = agentMapper.deleteById(agentId);
        if (result <= 0) {
            throw new BizException("删除 agent 失败");
        }
    }

    @Override
    public void updateAgent(String agentId, UpdateAgentRequest request) {
        try {
            // 查询现有?agent
            Agent existingAgent = agentMapper.selectById(agentId);
            if (existingAgent == null) {
                throw new BizException("Agent 不存? " + agentId);
            }
            
            // 将现?Agent 转换?AgentDTO
            AgentDTO agentDTO = agentConverter.toDTO(existingAgent);
            
            // 使用 UpdateAgentRequest 更新 AgentDTO
            agentConverter.updateDTOFromRequest(agentDTO, request);
            
            // 将更新后?AgentDTO 转换?Agent 实体
            Agent updatedAgent = agentConverter.toEntity(agentDTO);
            
            // 保留原有?ID 和创建时?
            updatedAgent.setId(existingAgent.getId());
            updatedAgent.setCreatedAt(existingAgent.getCreatedAt());
            updatedAgent.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据?
            int result = agentMapper.updateById(updatedAgent);
            if (result <= 0) {
                throw new BizException("更新 agent 失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新 agent 时发生序列化错误: " + e.getMessage());
        }
    }
}
