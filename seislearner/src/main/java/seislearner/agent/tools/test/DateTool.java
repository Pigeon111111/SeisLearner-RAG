package seislearner.agent.tools.test;

import seislearner.agent.tools.Tool;
import seislearner.agent.tools.ToolType;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTool implements Tool {
    @Override
    public String getName() {
        return "dateTool";
    }

    @Override
    public String getDescription() {
        return "获取当前的日期";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "getDate", description = "获取当前的日期")
    public String getDate() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
}
