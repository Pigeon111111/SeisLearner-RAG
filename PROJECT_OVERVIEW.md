# SeisLearner 项目速览手册

> **给无上下文 Agent 的 5 分钟快速入门指南**
>
> 最后更新：2026-04-04

---

## 一句话概括

SeisLearner 是一个**基于 Spring AI 的 RAG 智能体系统**——用户上传 PDF/文档到知识库，然后通过 AI Agent（Think-Execute 循环）进行对话式问答，Agent 会自动检索知识库、调用工具来回答问题。核心场景是地震勘探领域的专业教学。

---

## 系统架构全景

```
用户浏览器 (React 前端 :5173)
    │
    ▼  SSE + REST API
Spring Boot 后端 (:8080)
    │
    ├── AI 层 ─── Spring AI ChatClient → DeepSeek API / 智谱AI API
    │
    ├── Agent 引擎 ── Think(规划) → Execute(调用工具) → Loop(最多20轮)
    │                   │
    │                   ├── 工具1: search_knowledge_base (RAG检索)
    │                   ├── 工具2: terminate (结束任务)
    │                   └── 可扩展: 更多工具在 agent/tools/ 下
    │
    ├── RAG 管线 ──────────────────────────────────────┐
    │   1. 文档上传 → 2. MinerU解析 → 3. 分块 →        │
    │   4. Embedding → 5. 存入pgvector                  │
    │                                                      │
    │   查询时: 用户问题 → HybridRetrieval               │
    │   (稠密向量bge-m3 + 稀疏BM25) → Rerank → 返回上下文│
    │                                                      │
    └── 数据层 ── PostgreSQL + pgvector (:5434) ◄────────┘
                       │
                       ├── agent          (Agent配置表)
                       ├── chat_session   (会话表)
                       ├── chat_message   (消息表)
                       ├── knowledge_base (知识库表)
                       ├── document       (文档表)
                       └── chunk_bge_m3   (向量分块表, vector(1536))
```

---

## 技术栈一览

| 层 | 技术 | 版本/备注 |
|---|------|----------|
| **后端框架** | Spring Boot | 3.x, Java 17 |
| **AI 集成** | Spring AI | ChatClient + ToolCalling |
| **数据库** | PostgreSQL 17 + pgvector | Docker 容器 `seislearner-pg`, 端口 **5434** |
| **ORM** | MyBatis | XML 映射文件 |
| **向量模型** | qwen3-vl-embedding (DashScope) | 1536 维多模态, 通过阿里云 DashScope API 调用 |
| **LLM** | DeepSeek / 智谱AI GLM-4 | 二选一或同时配置 |
| **文档解析** | MinerU 官方在线 API (v2.1 三级策略) | https://mineru.net |
| **本地降级** | PyMuPDF (fitz) | 第三级兜底: 仅PDF, 毫秒级 |
| **前端** | React 19 + TypeScript + Ant Design | Vite 构建, 端口 5173 |
| **实时通信** | SSE (Server-Sent Events) | 检索过程实时推送前端 |
| **评估** | RAGAs | Python 独立服务 |

---

## 关键目录与文件索引

### 后端核心 (`seislearner/src/main/java/seislearner/`)

```
agent/
  SeisLearner.java              ★★★ Agent 核心引擎: Think-Execute循环, 状态机
  SeisLearnerFactory.java         Agent工厂, 创建实例并注入依赖
  AgentState.java                 枚举: IDLE → THINKING → EXECUTING → FINISHED/ERROR
  tools/
    SearchKnowledgeBaseTool.java  ★★ RAG检索工具, Agent调用的核心工具
    TerminateTool.java            终止任务工具

controller/
  AgentController.java           ★ Agent API: 发消息、创建会话
  DocumentController.java         文档上传API
  KnowledgeBaseController.java    知识库CRUD

service/
  impl/
    AgentFacadeServiceImpl.java  ★★ Agent门面: 协调ChatClient+Agent+工具+SSE
    DocumentFacadeServiceImpl.java ★ 文档处理门面: 上传→解析→分块→入库全流程
    MultimodalParserServiceImpl.java  调用MinerU服务(/file_parse)解析文档
    ChunkingServiceImpl.java      三级滑动窗口分块(1200/240, 600/120, 300/60)
    HybridRetrievalServiceImpl.java  混合检索: 向量+BM25+Rerank
    RecursiveRetrievalServiceImpl.java 递归检索: StepBack/HyDE/分解策略
    RerankServiceImpl.java        Rerank重排序(qwen3-vl-rerank)
    Bm25Calculator.java           BM25关键词匹配实现
    SseServiceImpl.java           SSE推送服务

config/
  AiConfig.java                   Spring AI Bean配置(ChatClient等)

model/
  entity/                        数据库实体类(对应6张表)
  dto/, vo/                      数据传输对象、视图对象
```

### 前端 (`ui/src/`)

```
components/
  EmptyAgentChatView.tsx         ★ 主聊天界面组件
  AgentSidebar.tsx               左侧Agent/知识库列表

api/                            HTTP请求封装(BASE_URL = localhost:8080)
hooks/                         自定义Hooks
contexts/                      React Context全局状态
types/                        TypeScript类型定义
App.tsx                        根组件
```

### 配置与服务脚本 (项目根目录)

```
seislearner/src/main/resources/
  application.yaml              ★★★ 后端主配置(DB/AI模型/MinerU地址/端口)

mineru_api_service.py           ★★★ MinerU API包装器(v2.1 三级解析策略)
ragas_evaluation_service.py     RAGAs评估服务(Python)
seislearner.sql                 ★ 数据库建表脚本
STARTUP.md                     启动指南(环境/步骤/排错)
README.md                      项目完整文档
```

---

## 启动步骤（快速版）

```bash
# 1. 启动PostgreSQL(Docker)
docker start seislearner-pg
# 端口映射: 宿主机5434 → 容器5432

# 2. 启动后端(Spring Boot)
cd seislearner
./mvnw spring-boot:run
# 监听 :8080, API前缀 /api

# 3. 启动前端(React/Vite)
cd ui
npm run dev
# 监听 :5173

# 4. MinerU文档解析服务(Python, 非Docker!)
python mineru_api_service.py
# 监听 :8000 (三级策略: Token API → Agent免登录API → PyMuPDF)
```

---

## 核心业务流程

### 流程1: 用户对话（Agent Think-Execute）

```
用户输入消息
  → POST /api/agent/{agentId}/chat
  → AgentFacadeServiceImpl.handleChat()
  → 创建/获取 ChatSession
  → 创建 SeisLearner(Agent)实例
  → 注入: ChatClient + Tools + KnowledgeBases + SSE
  → agent.run() 启动循环:
      for i in 1..20:
        think()   → LLM决定是否调用工具(带systemPrompt决策)
                    → 有toolCalls? → execute()
                                    → ToolCallingManager执行工具
                                    → 结果写回chatMemory
                    → 无toolCalls? → state=FINISHED, 输出最终回答
  → 所有消息通过SSE实时推送到前端
```

**关键**: Agent 不是简单的一问一答。它会在一个会话内**最多循环 20 次 Think-Execute**，每次可以调用工具（主要是搜索知识库），逐步推理出最终答案。

### 流程2: 文档入库（RAG管线）

```
用户上传PDF/MD/TXT
  → POST /api/document/upload
  → DocumentFacadeServiceImpl.uploadDocument()
  → 1. 保存document记录到DB
  → 2. MultimodalParserService.parseDocument()
     → 判断文件类型:
       .md → MarkdownParserService (直接读取文本)
       其他 → 调用 MinerU API (POST /file_parse) 解析为Markdown
  → 3. ChunkingService.chunkContent()
     → 三级滑动窗口分块: 1200/240, 600/120, 300/60字符
     → 自动合并短块
  → 4. 对每个chunk调用Embedding API (qwen3-vl-embedding, 1536维)
     → 注意: DB schema 已更新为 vector(1536), 与 qwen3-vl-embedding 模型匹配, HNSW索引已启用
  → 5. 将chunk+向量写入 chunk_bge_m3 表
```

### 流程3: RAG检索回答

```
用户提问
  → Agent think() 决定调用 search_knowledge_base 工具
  → SearchKnowledgeBaseTool.execute()
  → HybridRetrievalService.hybridSearch()
    → 并行执行:
       a) DenseSearch: 向量余弦相似度 (pgvector HNSW索引)
       b) SparseSearch: BM25关键词评分
    → 加权融合 (Reciprocal Rank Fusion)
    → Rerank重排序 (qwen3-vl-rerank, 取top-K)
  → 检索到的chunks作为上下文注入LLM
  → LLM基于上下文生成回答
```

---

## 重要配置参数

### application.yaml 关键项

```yaml
# 数据库 (端口是5434不是5432!)
spring.datasource.url: jdbc:postgresql://localhost:5434/seislearner

# LLM模型 (至少配一个, 参考 application-example.yaml)
spring.ai.deepseek.api-key: YOUR_DEEPSEEK_API_KEY
spring.ai.zhipuai.api-key: YOUR_ZHIPUAI_API_KEY

# MinerU文档解析服务地址
mineru.api.url: http://localhost:8000   # POST /file_parse

# Embedding (通过DashScope)
dashscope.api-key: YOUR_DASHSCOPE_API_KEY
embedding.model: qwen3-vl-embedding     # 1536维

# Rerank
rerank.model: qwen3-vl-rerank
```

### MinerU 服务 (v2.1 三级解析策略)

`mineru_api_service.py` 是纯 Python FastAPI 服务，**不再依赖 Docker**。

三级解析策略（自动降级）：

```
┌────────────────────────────────────────────────────────────┐
│  ① Token 精准 API  (/api/v4/extract/task)                 │
│     需要 MINERU_API_TOKEN, 高质量(公式/表格/OCR)            │
│     每天2000页免费额度                                       │
│     ↓ 失败或未配置Token                                     │
│  ② Agent 免登录 API  (/api/v1/agent/parse/file)           │
│     无需任何配置, IP限频, 限制20页/次                       │
│     ↓ 失败(超时/超页数/网络错误)                            │
│  ③ PyMuPDF 本地解析  (仅支持PDF)                           │
│     毫秒级完成, 无需网络, 不支持公式/OCR                     │
└────────────────────────────────────────────────────────────┘
```

环境变量控制:

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `MINERU_API_TOKEN` | MinerU官方Token (从 mineru.net 用户中心获取) | 空(跳过策略1) |
| `MINERU_USE_LOCAL` | 强制使用本地PyMuPDF | false |
| `MINERU_HOST` | 监听地址 | 0.0.0.0 |
| `MINERU_PORT` | 监听端口 | 8000 |

**启动方式** (二选一):
- **前台调试**: `python mineru_api_service.py`
- **后台运行**: 在PowerShell中用 `Start-Process -FilePath "python" -ArgumentList "mineru_api_service.py" -WindowStyle Hidden`

**依赖安装** (仅需一次):
```bash
pip install fastapi uvicorn httpx python-multipart PyMuPDF
```

---

## 数据库表结构速查

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| `agent` | Agent配置(id,name,description,system_prompt) | agent_id PK |
| `chat_session` | 会话(session_id, agent_id, user_id) | session_id PK |
| `chat_message` | 消息(role/content/session_id) | message_id PK |
| `knowledge_base` | 知识库(name, description) | kb_id PK |
| `document` | 文件(filename, filetype, kb_id, status) | document_id PK |
| `chunk_bge_m3` | 文本分块+向量(content, embedding vector(1536), doc_id) | chunk_id PK |

---

## 已知问题与注意事项

### 当前已修复的问题
1. **MinerU容器500错误** — tempfile变量冲突、CUDA sm_120不支持(RTX 5060 Ti)、libGL缺失 → 全部修复
2. **前端发送消息空白崩溃** — EmptyAgentChatView缺少try-catch → 已加错误处理
3. **文档入库后chunk为0** — 异常被静默吞掉 → 已加降级方案和明确报错

### 需要注意的点
1. ~~**向量维度不匹配风险**: 已修复~~ DB和模型均为1536维, HNSW索引已启用。
2. **MinerU服务已改为纯Python**: v2.1 不再需要本地Docker镜像。**可以安全删除旧镜像释放约41GB空间**:
   ```bash
   # 确认已删除(如果还在的话):
   docker stop mineru-api 2>$null; docker rm mineru-api 2>$null
   docker rmi jianjungki/mineru:latest 2>$null
   ```
3. **三级降级行为**: 即使不设任何Token, 服务也能工作: Agent免登录API(免费, 限制20页/次) → PyMuPDF本地解析(PDF兜底)。非PDF格式在无Token且Agent API失败时会返回503。
4. **Agent API 页数限制**: 免登录版限制单次20页。超页数PDF会自动降级到PyMuPDF。如需完整高质量解析，建议设置 `MINERU_API_TOKEN`。

---

## 常见开发操作

### 添加新的Agent工具
1. 在 `agent/tools/` 下新建工具类, 实现 `ToolCallback`
2. 在 `SeisLearnerFactory.java` 中注册到工具列表
3. Agent的think()阶段就能自动发现并调用该工具

### 添加新的文档解析格式
1. 在 `MultimodalParserServiceImpl.java` 中添加新的 MIME 类型分支
2. 或在 `mineru_api_service.py` 中添加对应处理逻辑

### 调试Agent思考过程
- 查看 Spring Boot 控制台日志: `[ToolCalling]` 前缀的工具调用详情
- 前端SSE实时显示: 检索事件、策略切换、状态变化
- DB直接查: `SELECT * FROM chat_message WHERE session_id=? ORDER BY created_at`

---

## 文件位置汇总

| 内容 | 相对路径 |
|------|----------|
| 项目根目录 | `./` |
| 后端代码 | `seislearner/src/main/java/seislearner/` |
| 后端配置 | `seislearner/src/main/resources/application.yaml` |
| 前端代码 | `ui/src/` |
| MinerU服务脚本 | `mineru_api_service.py` |
| 数据库初始化SQL | `seislearner.sql` |
| README | `README.md` |
| **本概览手册** | `PROJECT_OVERVIEW.md` |
