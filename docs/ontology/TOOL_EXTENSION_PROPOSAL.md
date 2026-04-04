# SeisLearner Agent 工具扩展方案

> 基于 Copilot CLI、Nanobot、AutoGPT、LangGraph、Spring AI 1.1 的研究成果
> 最后更新：2026-04-04

---

## 一、当前工具架构分析

### 现状
- **Tool 接口**: `seislearner.agent.tools.Tool` (getName/getDescription/getType)
- **ToolType**: FIXED（所有Agent都有） / OPTIONAL（Agent配置选择）
- **注册机制**: `@Component` + `implements Tool` → Spring IoC 自动注入 `ToolFacadeServiceImpl`
- **限制**: 工具硬编码，需修改代码+重启，不支持运行时动态扩展

### 当前工具清单
| 工具 | 类型 | 状态 | 说明 |
|------|------|------|------|
| TerminateTool | FIXED | 启用 | 终止Agent循环 |
| KnowledgeTools | FIXED | 启用 | RAG语义检索 |
| DirectAnswerTool | FIXED | 禁用 | 直接回答(@Component注释) |
| DataBaseTools | OPTIONAL | 启用 | PostgreSQL只读查询 |
| EmailTools | OPTIONAL | 启用 | QQ邮件发送 |
| FileSystemTools | OPTIONAL | 禁用 | 文件系统操作(@Component注释) |

---

## 二、参考项目架构对比

### 2.1 Copilot CLI — 多源聚合注册
- 四层工具来源：内置工具 → MCP工具 → 插件工具 → Shell工具
- 五层权限控制：YOLO模式 → 会话缓存 → 位置缓存 → 安全命令白名单 → 用户确认
- 执行流水线：请求验证 → 权限检查 → preToolUse钩子 → 执行路由 → postToolUse钩子
- 支持并行工具执行 + 批量权限请求

### 2.2 Nanobot — 极简声明式
- Tool 抽象基类 + Tool Registry
- ContextBuilder 将工具描述自动注入 System Prompt
- 扩展流程：创建工具文件 → 编写函数+描述 → 注册到 Registry
- 约4000行代码实现完整Agent框架

### 2.3 AutoGPT — 协议化插件
- 协议接口 + 配置驱动 + 反射自动发现
- YAML `enabled: true/false` 控制运行时加载
- `run_after()` 声明组件依赖和初始化顺序
- 支持打包为 PyPI 插件分发

### 2.4 LangGraph — 状态驱动动态工具
- 不一次性提供所有工具，按任务阶段动态控制可用工具集
- 应用场景：工作流强制执行、渐进式推理引导、安全性提升

### 2.5 Spring AI 1.1 — MCP 原生支持
- `@McpTool` 注解定义工具 → 自动暴露为 MCP 工具
- `McpToolUtils.getToolCallbacksFromSyncClients()` → MCP工具转Spring AI ToolCallback
- Boot Starter: `spring-ai-starter-mcp-server` / `spring-ai-starter-mcp-client`
- 传输协议: STDIO / SSE / Streamable HTTP

---

## 三、推荐扩展方案

### 阶段1: 工具注册中心 + 数据库驱动（近期可实现）

改造核心：将工具注册从 Spring IoC 硬编码迁移到数据库+动态注册。

```java
// 新增 ToolRegistry 接口
public interface ToolRegistry {
    void registerTool(ToolDefinition definition, Object toolInstance);
    void unregisterTool(String toolName);
    List<ToolCallback> getToolCallbacks(String agentId);
    List<ToolDefinition> getAvailableTools();
}

// ToolDefinition 数据库表
CREATE TABLE tool_definition (
    id UUID PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    description TEXT NOT NULL,
    type VARCHAR(20) NOT NULL,  -- FIXED / OPTIONAL
    tool_class VARCHAR(200),     -- Spring Bean 名称或全限定类名
    config JSONB,               -- 工具配置参数
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

// agent_tool_binding 表 — Agent与工具的绑定关系
CREATE TABLE agent_tool_binding (
    agent_id UUID REFERENCES agent(id) ON DELETE CASCADE,
    tool_name VARCHAR(100) REFERENCES tool_definition(name) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, tool_name)
);
```

### 阶段2: MCP 客户端集成（中期）

利用 Spring AI 1.1 原生 MCP 支持，让 Agent 能调用外部 MCP 服务器提供的工具。

```xml
<!-- pom.xml 新增依赖 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

```yaml
# application.yaml 配置外部 MCP 服务器
spring:
  ai:
    mcp:
      client:
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

```json
// mcp-servers.json — 配置外部工具服务器
{
  "mcpServers": {
    "weather": {
      "command": "npx",
      "args": ["-y", "weather-mcp-server"],
      "type": "stdio"
    },
    "web-search": {
      "url": "http://localhost:3001/mcp",
      "type": "sse"
    }
  }
}
```

```java
// 修改 SeisLearnerFactory，合并本地工具 + MCP 工具
private List<ToolCallback> buildToolCallbacks(List<Tool> localTools) {
    List<ToolCallback> callbacks = buildLocalToolCallbacks(localTools);
    
    // 合并 MCP 客户端的工具
    List<McpSyncClient> mcpClients = mcpClientManager.getSyncClients();
    List<ToolCallback> mcpCallbacks = McpToolUtils.getToolCallbacksFromSyncClients(mcpClients);
    callbacks.addAll(mcpCallbacks);
    
    return callbacks;
}
```

### 阶段3: MCP Server 暴露（让其他 Agent 调用 SeisLearner）

将 SeisLearner 的工具（KnowledgeTool、DataBaseTool 等）通过 MCP Server 暴露出去。

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    mcp:
      server:
        name: seislearner-mcp
        type: SYNC
        protocol: SSE
        port: 8081
```

### 阶段4: Skill/Plugin 系统（长期）

借鉴 Nanobot 的极简设计，实现 Skill 插件系统：

```java
// Skill 接口
public interface Skill {
    String getName();
    String getDescription();
    List<ToolDefinition> getProvidedTools();
    void initialize(SkillContext context);
    void destroy();
}

// Skill 加载器 — 支持从 JAR、脚本、目录加载
public interface SkillLoader {
    List<Skill> loadSkills(SkillConfig config);
    boolean supports(String skillType);
}

// 示例：WebSearchSkill
@Component
public class WebSearchSkill implements Skill {
    @Override
    public String getName() { return "web-search"; }
    
    @Override
    public String getDescription() { return "网络搜索能力"; }
    
    @Override
    public List<ToolDefinition> getProvidedTools() {
        return List.of(
            ToolDefinition.of("webSearch", "搜索互联网内容", this::search),
            ToolDefinition.of("fetchUrl", "获取网页内容", this::fetchUrl)
        );
    }
}
```

---

## 四、OpenAI Function Calling 最佳实践（适用于工具设计）

1. **工具数量控制**: 单次请求不超过 20 个工具
2. **函数命名清晰**: 直白命名如 `get_delivery_date`，避免缩写
3. **参数设计规范**: 使用枚举限制取值、明确标注必填、禁止额外属性
4. **描述要精确**: 在描述中明确调用场景
5. **启用 strict 模式**: 确保模型参数严格遵循 JSON Schema
6. **错误处理**: 检查 `finish_reason`，处理 `length`/`content_filter` 等情况

---

## 五、实施优先级

| 优先级 | 方案 | 预计工作量 | 收益 |
|--------|------|-----------|------|
| P0 | 工具注册中心 + DB驱动 | 3-5天 | 解耦硬编码，支持运行时管理 |
| P1 | MCP Client 集成 | 2-3天 | 接入丰富的外部工具生态 |
| P2 | MCP Server 暴露 | 1-2天 | 被其他 Agent 调用 |
| P3 | Skill/Plugin 系统 | 5-7天 | 完全可扩展的插件架构 |
