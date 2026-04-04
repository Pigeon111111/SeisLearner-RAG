# SeisLearner

<p align="center">
  <strong>基于 Spring AI + React 的 RAG 智能体系统</strong><br>
  多模态文档解析 / 混合向量检索 / Agent 自主推理 / SSE 实时可视化
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-green" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Spring_AI-1.1-blue" alt="Spring AI">
  <img src="https://img.shields.io/badge/React-19-61dafb" alt="React">
  <img src="https://img.shields.io/badge/PostgreSQL-17-336791" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
</p>

---

## Overview

SeisLearner 是一个面向地震勘探教学领域的 RAG（Retrieval-Augmented Generation）智能体系统。用户上传 PDF/Markdown/TXT 教材到知识库后，通过 AI Agent 进行多轮对话式问答。Agent 采用 Think-Execute 循环架构，自主决定是否需要检索知识库，并通过 SSE 实时展示推理过程。

一句话概括：**上传教材，提问，AI 基于你的教材回答问题。**

### Key Features

- **Agent Think-Execute Loop** - 最多 15 轮自主推理循环，Agent 自行决定检索时机
- **Hybrid Vector Retrieval** - 稠密向量（语义）+ 稀疏 BM25（关键词）+ Rerank 重排序
- **Multi-level Document Parsing** - MinerU API 三级自动降级：Token 精准 > Agent 免费 > PyMuPDF 本地兜底
- **Recursive Retrieval** - 支持 Step-Back、HyDE、问题分解等高级检索策略
- **Conversation Summarization** - 对话上下文自动摘要压缩，长对话不丢失历史
- **Real-time Visualization** - SSE 推送 Agent 思考过程、工具调用、检索步骤到前端

---

## Architecture

```
Browser (React :5173)
    |
    v  SSE + REST
Spring Boot (:8080)
    |
    +-- Agent Engine -- Think --> Execute --> Loop (max 15 rounds)
    |                     |
    |                     +-- Tool: search_knowledge_base (RAG)
    |                     +-- Tool: terminate
    |
    +-- RAG Pipeline ---- Upload --> Parse --> Chunk --> Embed --> Store
    |                     Query --> Hybrid Search --> Rerank --> Context
    |
    +-- Data Layer ----- PostgreSQL + pgvector (:5434)
                          agent / chat_session / chat_message
                          knowledge_base / document / chunk_bge_m3
```

---

## Tech Stack

| Layer | Technology | Notes |
|-------|-----------|-------|
| Backend | Spring Boot 3.5 + Java 17 | |
| AI Framework | Spring AI 1.1 | ChatClient + ToolCalling |
| LLM | DeepSeek / 智谱AI GLM-4 | Configurable |
| Embedding | qwen3-vl-embedding (1536d) | Via DashScope API |
| Rerank | qwen3-vl-rerank | Via DashScope API |
| Database | PostgreSQL 17 + pgvector | HNSW index |
| ORM | MyBatis | XML mappers |
| Document Parsing | MinerU v2.1 (3-level fallback) | Python FastAPI |
| Frontend | React 19 + TypeScript + Ant Design | Vite build |
| Real-time | SSE (Server-Sent Events) | |

---

## Prerequisites

- Java 17+
- Maven 3.8+ (or use the included `mvnw` wrapper)
- Node.js 18+
- Docker (for PostgreSQL)
- Python 3.9+ (for MinerU document parsing service)

---

## Quick Start

### 1. Clone & Configure

```bash
git clone https://github.com/Pigeon111111/seislearner.git
cd seislearner
```

Copy the example config and fill in your API keys:

```bash
cp seislearner/src/main/resources/application-example.yaml \
   seislearner/src/main/resources/application.yaml
```

Required API keys in `application.yaml`:

| Key | Provider | Purpose |
|-----|----------|---------|
| `spring.ai.deepseek.api-key` | [DeepSeek](https://platform.deepseek.com) | LLM inference |
| `dashscope.api-key` | [DashScope](https://dashscope.console.aliyun.com) | Embedding + Rerank |

### 2. Start PostgreSQL

```bash
docker run -d --name seislearner-pg \
  -e POSTGRES_PASSWORD=your_password \
  -e POSTGRES_DB=seislearner \
  -p 5434:5432 \
  pgvector/pgvector:pg17

# Initialize database schema
cat seislearner.sql | docker exec -i seislearner-pg psql -U postgres -d seislearner
```

### 3. Start MinerU Document Service (optional but recommended)

```bash
pip install fastapi uvicorn httpx python-multipart PyMuPDF
python mineru_api_service.py
# Listens on :8000
```

> Without MinerU, PDF parsing falls back to PyMuPDF (text-only, no formula/table support).

### 4. Start Backend

```bash
cd seislearner
./mvnw spring-boot:run
# Listens on :8080
```

### 5. Start Frontend

```bash
cd ui
npm install
npm run dev
# Listens on :5173
```

Open **http://localhost:5173/** in your browser.

---

## Project Structure

```
seislearner/
├── seislearner/                     # Spring Boot backend
│   └── src/main/
│       ├── java/seislearner/
│       │   ├── agent/               # Agent engine (Think-Execute)
│       │   │   ├── SeisLearner.java         # Core agent logic + state machine
│       │   │   ├── SeisLearnerFactory.java  # Agent factory with DI
│       │   │   └── tools/                 # Agent-callable tools
│       │   ├── controller/          # REST API endpoints
│       │   ├── service/impl/        # Business logic
│       │   │   ├── HybridRetrievalServiceImpl.java   # Dense + Sparse + Fusion
│       │   │   ├── RecursiveRetrievalServiceImpl.java # Multi-strategy retrieval
│       │   │   ├── RerankServiceImpl.java            # Cross-encoder reranking
│       │   │   ├── DocumentFacadeServiceImpl.java   # Upload -> Parse -> Chunk -> Store
│       │   │   └── ConversationSummarizer.java       # Context compression
│       │   ├── model/               # Entity, DTO, VO
│       │   ├── mapper/              # MyBatis mappers
│       │   └── config/              # Spring configuration
│       └── resources/
│           ├── application-example.yaml   # Config template
│           └── mapper/*.xml              # SQL mappings
├── ui/                             # React frontend
│   └── src/
│       ├── components/             # Chat UI components
│       ├── api/                    # HTTP client
│       └── types/                  # TypeScript definitions
├── mineru_api_service.py           # MinerU document parsing service
├── ragas_evaluation_service.py     # RAG evaluation service (optional)
├── seislearner.sql                 # Database schema
├── evaluation/                     # Evaluation configs & test data
└── LICENSE
```

---

## Database Schema

| Table | Purpose |
|-------|---------|
| `agent` | AI agent configurations (system prompt, tools, knowledge bases) |
| `chat_session` | Conversation sessions with auto-generated summaries |
| `chat_message` | Chat history (user / assistant / system / tool roles) |
| `knowledge_base` | User-created knowledge base containers |
| `document` | Uploaded document metadata |
| `chunk_bge_m3` | Text chunks with embedding vectors (VECTOR(1536) + HNSW) |

---

## Core Workflows

### Agent Dialogue Flow

```
User input -> POST /api/agent/{id}/chat
  -> Create/resume ChatSession
  -> Build SeisLearner instance (inject ChatClient, Tools, Memory, SSE)
  -> agent.run() loop (max 15 rounds):
      think()  -> LLM decides: call tool or answer directly?
      execute() -> ToolCallingManager executes tools, results feed back to context
      -> No tool calls? -> FINISHED, output final answer
  -> All messages + tool calls streamed to frontend via SSE
  -> Post-run: summarize conversation, persist to chat_session
```

### Document Ingestion Pipeline

```
Upload PDF/MD/TXT -> Save document record
  -> MultimodalParserService:
      .md  -> Direct text extraction
      .pdf -> MinerU API (3-level fallback) -> Markdown text
  -> ChunkingService: Sliding window (1200/240, 600/120, 300/60 chars)
  -> DashScope Embedding API -> VECTOR(1536)
  -> Store in chunk_bge_m3 with HNSW index
```

### Hybrid Retrieval

```
User query -> HybridRetrievalService:
  Parallel:
    a) Dense search: Cosine similarity via pgvector HNSW index
    b) Sparse search: BM25 keyword scoring via PostgreSQL GIN index
  -> Reciprocal Rank Fusion (weighted merge)
  -> Rerank (qwen3-vl-rerank cross-encoder)
  -> Top-K chunks as context -> LLM generates answer
```

---

## Configuration

See `application-example.yaml` for all configuration options.

Key environment variables for MinerU service:

| Variable | Description | Default |
|----------|------------|---------|
| `MINERU_API_TOKEN` | MinerU official token (from [mineru.net](https://mineru.net)) | Empty (skip level 1) |
| `MINERU_USE_LOCAL` | Force PyMuPDF local parsing | `false` |
| `MINERU_PORT` | MinerU service port | `8000` |

---

## Extending

### Adding a New Agent Tool

1. Create a class in `agent/tools/` with `@Tool` annotation
2. Register in `ToolFacadeService`
3. Agent automatically discovers and can call it during Think phase

### Adding a New Document Format

1. Add MIME type handling in `MultimodalParserServiceImpl`
2. Or extend `mineru_api_service.py` with additional parsers

---

## License

This project is licensed under the [MIT License](LICENSE).

---

## Acknowledgments

- [Spring AI](https://spring.io/projects/spring-ai) - AI integration framework
- [MinerU](https://mineru.net) - Document parsing engine
- [DashScope](https://dashscope.aliyun.com) - Embedding & Rerank models
- [pgvector](https://github.com/pgvector/pgvector) - Vector similarity search for PostgreSQL
