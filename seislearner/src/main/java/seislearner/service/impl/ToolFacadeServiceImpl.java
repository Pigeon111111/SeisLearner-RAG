package seislearner.service.impl;

import seislearner.agent.tools.Tool;
import seislearner.agent.tools.ToolType;
import seislearner.service.ToolFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;

    @Override
    public List<Tool> getAllTools() {
        return tools;
    }

    @Override
    public List<Tool> getOptionalTools() {
        return getToolsByType(ToolType.OPTIONAL);
    }

    @Override
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .toList();
    }
}
