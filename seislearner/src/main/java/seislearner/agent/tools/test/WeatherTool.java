package seislearner.agent.tools.test;

import seislearner.agent.tools.Tool;
import seislearner.agent.tools.ToolType;
import org.springframework.stereotype.Component;

@Component
public class WeatherTool implements Tool {

    @Override
    public String getName() {
        return "weatherTool";
    }

    @Override
    public String getDescription() {
        return "获取天气";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(name = "weather", description = "获取天气")
    public String getWeather(String city, String date) {
        // 模拟模拟调用天气 API
        return city + date + "的天气查询结果：晴转多云，温?25°C，湿?60%";
    }
}
