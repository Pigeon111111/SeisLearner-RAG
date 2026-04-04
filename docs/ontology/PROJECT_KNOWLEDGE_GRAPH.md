# SeisLearner 项目知识图谱 (Project Knowledge Graph)

> 为新 Agent 提供完整的项目上下文。包含所有模块、依赖关系、数据流、API 端点。
> 最后更新：2026-04-04

---

## 1. 系统架构总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        SeisLearner RAG Agent System                      │
├─────────────┬──────────────────┬──────────────────┬────────────────────┤
│   前端 (UI)  │   后端 (Spring)   │   文档解析服务    │    数据库 (PG)     │
│  React + Vite │  Boot 3.5.8     │   MinerU Python  │   + pgvector      │
│   Port 5173  │   Port 8080      │    Port 8000     │    Port 5434      │
└──────┬──────┴────────┬─────────┴────────┬─────────┴────────┬───────────┘
       │               │                  │                  │
       │  REST + SSE   │  HTTP API        │  HTTP (解析)      │  JDBC/SQL
       ▼               ▼                  ▼                  ▼
  WebSocket/SSE   Controller层       文档分块+Embedding    向量+关系存储
```

### 技术栈
- **后端**: Spring Boot 3.5.8 + Spring AI 1.1.0 + MyBatis 3.5.14 + PostgreSQL + pgvector
- **前端**: React 19 + TypeScript + Vite + TailwindCSS + shadcn/ui
- **文档解析**: MinerU API (Python) → 分块 → qwen3-vl-embedding (DashScope, 1536维)
- **LLM**: 多模型支持 (DeepSeek, 智谱AI, OpenAI) via Spring AI ChatClient
- **向量检索**: 混合检索 (稠密70% + 稀疏30%) + Rerank (qwen3-vl-rerank)
- **通信**: REST API + SSE (Server-Sent Events)

---

## 2. 后端模块依赖图

```
                          ┌─────────────────┐
                          │   Controller 层  │
                          │ (8个REST控制器)   │
                          └────────┬────────┘
                                   │ 调用
                          ┌────────▼────────┐
                          │  Facade Service  │
                          │   (业务门面层)    │
                          └────────┬────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
     ┌────────▼───────┐  ┌───────▼────────┐  ┌────────▼───────┐
     │  Agent 核心     │  │  RAG 检索链     │  │  文档处理链     │
     │  (Think-Execute)│  │  (混合+递归)    │  │  (解析+分块)   │
     └────────┬───────┘  └───────┬────────┘  └────────┬───────┘
              │                  │                    │
     ┌────────▼───────┐  ┌──────▼─────────┐  ┌───────▼────────┐
     │  Tool 系统      │  │  向量+全文检索   │  │  MinerU HTTP   │
     │  (Spring IoC)   │  │  + Rerank       │  │  + Chunking    │
     └────────────────┘  └────────────────┘  └────────────────┘
```

---

## 3. 核心模块详解

### 3.1 Agent 核心循环 (Think-Execute Loop)

```
用户消息 → loadMemory(历史摘要+近期消息) → SeisLearner.run()
                                                  │
                                    ┌─────────────┴─────────────┐
                                    │     MAX_STEPS=15 循环       │
                                    │                            │
                              ┌─────▼──────┐             ┌──────▼─────┐
                              │   think()  │──无工具──→  │  FINISHED  │
                              │ LLM决策    │             └────────────┘
                              └─────┬──────┘
                                    │有工具调用
                              ┌─────▼──────┐
                              │  execute()  │
                              │ 执行工具    │
                              └─────┬──────┘
                                    │
                              ┌─────▼──────┐
                              │ 结果回记忆  │
                              │ 下一步think │
                              └────────────┘
```

**关键文件:**
- `agent/SeisLearner.java` — Agent 主类，Think-Execute 循环
- `agent/SeisLearnerFactory.java` — Agent 工厂，组装 ToolCallback + ChatMemory + ChatClient
- `agent/AgentState.java` — 状态枚举 (IDLE/RUNNING/FINISHED/ERROR)
- `config/ChatClientRegistry.java` — 多模型 ChatClient 注册表
- `config/MultiChatClientConfig.java` — 多模型配置 (DeepSeek, 智谱, OpenAI)

### 3.2 Tool 工具系统

```
Tool 接口 (getName/getDescription/getType)
    │
    ├── FIXED (固定工具, 所有Agent都有)
    │   ├── TerminateTool      — 终止Agent循环 (@Component)
    │   ├── KnowledgeTools      — RAG语义检索 (@Component)
    │   └── [DirectAnswerTool]  — 直接回答 (已禁用, @Component注释掉)
    │
    └── OPTIONAL (可选工具, Agent配置中选择)
        ├── DataBaseTools       — PostgreSQL只读查询 (@Component)
        ├── EmailTools          — QQ邮件发送 (@Component)
        ├── [FileSystemTools]   — 文件系统操作 (已禁用, @Component注释掉)
        └── test/WeatherTool, DateTool, CityTool — 测试工具 (@Component)
```

**注册机制:**
1. 所有 `@Component` + `implements Tool` 的类 → Spring IoC 自动注入
2. `ToolFacadeServiceImpl` 持有 `List<Tool> tools` (构造器注入)
3. `getFixedTools()` / `getOptionalTools()` 按 `ToolType` 过滤
4. `SeisLearnerFactory.resolveRuntimeTools()` 合并固定+Agent配置中的可选工具
5. `buildToolCallbacks()` → `MethodToolCallbackProvider` 转 Spring AI `ToolCallback`

**⚠️ 当前限制:**
- 工具是**硬编码**的，必须 `implements Tool` + `@Component`
- 不支持运行时动态添加/移除工具
- 没有 MCP (Model Context Protocol) 集成
- 没有 Skill/Plugin 系统

### 3.3 RAG 检索链

```
KnowledgeTools.knowledgeQuery(kbsId, query)
    │
    ▼
RecursiveRetrievalService.recursiveSearchDetailedWithSources()
    │
    ├── 第1轮: HybridRetrievalService.hybridSearchDetailed()
    │       ├── denseSearch() — qwen3-vl-embedding 向量余弦相似度 (pgvector HNSW)
    │       └── sparseSearch() — PostgreSQL tsvector 全文检索 (GIN索引)
    │       └── 合并: 稠密×0.7 + 稀疏×0.3 → 去重 → Rerank
    │
    ├── 置信度判断 (>0.5 → 直接返回, ≤0.5 → 递归)
    │
    └── 第2~4轮: 用上轮结果关键词重新检索 (最多4轮, 累积≤15条)
```

**关键服务:**
- `RagService` — 基础向量检索 (DashScope embedding API)
- `HybridRetrievalServiceImpl` — 混合检索 (向量+全文, 权重合并)
- `RecursiveRetrievalServiceImpl` — 递归检索 (多轮, 字符预算12000)
- `RerankService` — 重排序 (DashScope rerank API)
- `ConversationSummarizer` — 对话摘要 (LLM总结, 两层记忆)

### 3.4 文档处理链

```
上传文件(MultipartFile)
    │
    ▼
DocumentFacadeServiceImpl.uploadDocument()
    │
    ├── 1. 保存原始文件 → data/documents/{kbId}/{filename}
    │
    ├── 2. 调用 MinerU API (localhost:8000) 解析文档
    │       三级策略: Token API → Agent免登录API → PyMuPDF本地兜底
    │
    ├── 3. MarkdownParserService → 提取文本内容
    │
    ├── 4. ChunkingService → 文本分块 (按段落/字符数)
    │
    └── 5. RagService → 每块生成向量 (qwen3-vl-embedding 1536维)
            → 存入 chunk_bge_m3 表 (pgvector)
```

### 3.5 消息与 SSE 通信

```
前端 → POST /api/chat-session/{id}/message → ChatMessageController
                                                     │
                                    ChatSessionFacadeServiceImpl.sendMessage()
                                                     │
                                    ┌────────────────┴────────────────┐
                                    │                                 │
                              持久化UserMessage              SeisLearnerFactory.create()
                              (ChatMessageMapper)                    │
                                                         SeisLearner.run() (异步)
                                                                    │
                                                         think() → LLM调用
                                                         execute() → 工具执行
                                                                    │
                                                         每步通过 SseService
                                                         推送 SSE 事件到前端
                                                                    │
                                                         ┌─────────┴──────────┐
                                                         │ SSE 事件类型:       │
                                                         │ AI_GENERATED_CONTENT │
                                                         │ TOOL_CALL           │
                                                         │ TOOL_RESPONSE       │
                                                         │ ERROR               │
                                                         │ COMPLETED           │
                                                         └────────────────────┘
```

### 3.6 对话记忆系统 (两层结构)

```
loadMemory(chatSessionId)
    │
    ├── 第1层: 历史对话摘要
    │   chat_session.conversation_summary
    │   → 作为 SystemMessage 注入: "【历史对话摘要】..."
    │
    └── 第2层: 近期原始消息
        chat_message 表 (最近 N 条, N由Agent配置的messageLength决定)
        → UserMessage / AssistantMessage(含toolCalls) / ToolResponseMessage
```

**对话结束后:**
```
summarizeAndPersist()
    │
    ├── ConversationSummarizer.summarize() — LLM总结本轮对话
    ├── 读取旧摘要 (chat_session.conversation_summary)
    ├── ConversationSummarizer.mergeSummaries() — 合并新旧摘要
    └── chatSessionMapper.updateConversationSummary() — 持久化
```

---

## 4. REST API 端点

### 4.1 Agent 管理 (`/api/agent`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/agent | 获取所有Agent列表 |
| GET | /api/agent/{id} | 获取Agent详情 |
| POST | /api/agent | 创建Agent |
| PUT | /api/agent/{id} | 更新Agent |
| DELETE | /api/agent/{id} | 删除Agent |

### 4.2 聊天会话 (`/api/chat-session`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/chat-session | 获取所有会话 |
| GET | /api/chat-session/{id} | 获取会话详情 |
| POST | /api/chat-session | 创建会话 (绑定Agent) |
| DELETE | /api/chat-session/{id} | 删除会话 |
| POST | /api/chat-session/{id}/message | 发送消息 (触发Agent) |

### 4.3 聊天消息 (`/api/chat-message`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/chat-message/session/{sessionId} | 获取会话消息历史 |

### 4.4 知识库 (`/api/knowledge-base`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/knowledge-base | 获取所有知识库 |
| POST | /api/knowledge-base | 创建知识库 |
| PUT | /api/knowledge-base/{id} | 更新知识库 |
| DELETE | /api/knowledge-base/{id} | 删除知识库 (级联: chunks→documents→KB) |

### 4.5 文档管理 (`/api/document`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/document | 获取所有文档 |
| GET | /api/document/kb/{kbId} | 获取知识库下的文档 |
| POST | /api/document | 创建文档记录 |
| POST | /api/document/upload | 上传文件 (自动解析+分块+向量化) |
| PUT | /api/document/{id} | 更新文档 |
| DELETE | /api/document/{id} | 删除文档 (级联清理chunks+物理文件) |

### 4.6 SSE 推送 (`/api/sse`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/sse/{sessionId} | SSE 订阅 (EventSource) |

### 4.7 工具管理 (`/api/tool`)
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/tool | 获取所有可用工具列表 |

---

## 5. 数据库模型 (PostgreSQL + pgvector)

```
agent (智能体)
  ├── id: UUID PK
  ├── name, description, system_prompt
  ├── model: TEXT (ChatClient标识)
  ├── chat_options: JSONB (messageLength等)
  └── allowed_tools: TEXT[] / allowed_kbs: TEXT[]

chat_session (聊天会话)
  ├── id: UUID PK
  ├── agent_id: UUID FK → agent(id)
  ├── title, metadata: JSONB
  ├── conversation_summary: TEXT (历史摘要)
  └── created_at, updated_at

chat_message (聊天消息)
  ├── id: UUID PK
  ├── session_id: UUID FK → chat_session(id)
  ├── role: TEXT (USER/ASSISTANT/SYSTEM/TOOL)
  ├── content: TEXT
  └── metadata: JSONB (toolCalls/toolResponse)

knowledge_base (知识库)
  ├── id: UUID PK
  ├── name, description
  └── created_at, updated_at

document (文档)
  ├── id: UUID PK
  ├── kb_id: UUID FK → knowledge_base(id) ON DELETE CASCADE
  ├── filename, file_path, file_type
  ├── metadata: JSONB
  └── created_at, updated_at

chunk_bge_m3 (文档分块 + 向量)
  ├── id: UUID PK
  ├── kb_id: UUID FK → knowledge_base(id) ON DELETE CASCADE
  ├── doc_id: UUID FK → document(id) ON DELETE CASCADE
  ├── content: TEXT
  ├── embedding: vector(1536) — HNSW索引, cosine距离
  ├── chunk_index: INT
  └── metadata: JSONB
```

**关键索引:**
- `idx_chunk_embedding` — pgvector HNSW (m=16, ef_construction=128, cosine)
- `idx_chunk_content_fts` — GIN索引 (tsvector全文检索)
- `idx_chunk_doc_id`, `idx_chunk_kb_id` — B-tree索引

---

## 6. 外部服务依赖

```
SeisLearner (Spring Boot :8080)
    │
    ├─── PostgreSQL (:5434) — 主数据库 + pgvector
    │
    ├─── DashScope API (阿里云百炼)
    │       ├── qwen3-vl-embedding — 多模态Embedding (1536维)
    │       └── qwen3-vl-rerank — 重排序
    │       配置: dashscope.api-key
    │
    ├─── LLM API (多模型)
    │       ├── DeepSeek (spring.ai.deepseek.api-key)
    │       ├── 智谱AI (spring.ai.zhipuai.api-key)
    │       └── OpenAI (spring.ai.openai.api-key)
    │
    └─── MinerU API (localhost:8000) — 文档解析
            三级策略: Token API → Agent免登录API → PyMuPDF本地
```

---

## 7. 前端架构 (React + TypeScript)

```
ui/src/
├── api/              — API 调用封装 (axios)
├── components/       — UI 组件
│   ├── ChatInterface.tsx      — 主聊天界面
│   ├── MessageList.tsx        — 消息列表
│   ├── MessageBubble.tsx      — 消息气泡 (Markdown渲染)
│   ├── ToolResponseDisplay.tsx — 工具调用结果展示
│   ├── Sidebar.tsx            — 侧边栏 (会话/知识库/Agent)
│   └── KnowledgeBasePanel.tsx — 知识库管理面板
├── contexts/         — React Context (会话/知识库状态)
├── hooks/            — 自定义 Hooks (useSSE, useChat等)
├── layout/           — 布局组件
├── types/            — TypeScript 类型定义
├── utils/            — 工具函数
├── App.tsx           — 应用入口
└── index.css         — TailwindCSS 全局样式
```

---

## 8. 关键设计决策

1. **Agent Loop 模式**: Think-Execute 循环 (非 ReAct), LLM 在 think() 中同时决定是否调用工具
2. **关闭自动工具执行**: `internalToolExecutionEnabled=false`, 手动管理工具执行和结果注入
3. **两层记忆**: 历史摘要 (SystemMessage) + 近期原始消息 (可配置窗口大小)
4. **混合检索**: 稠密向量 (70%) + 稀疏全文 (30%) 合并排序 + Rerank
5. **递归检索**: 置信度驱动的多轮检索 (最多4轮), 字符预算控制
6. **SSE 实时推送**: Agent 每一步通过 SSE 推送消息到前端, 支持工具调用可视化
7. **工具固定注册**: 通过 Spring IoC + `@Component` 自动发现, Tool 接口统一抽象

---

## 9. 配置文件

- `seislearner/src/main/resources/application.yaml` — 主配置 (API Key, DB连接, 端口) [已gitignore]
- `seislearner/src/main/resources/application-example.yaml` — 脱敏配置模板
- `seislearner.sql` — 数据库初始化SQL (建表+索引+约束)
- `ui/package.json` — 前端依赖
- `scripts/services.ps1` — PowerShell 服务管理脚本
