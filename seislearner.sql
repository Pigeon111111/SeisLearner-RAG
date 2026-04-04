-- ============================================================
-- SeisLearner 数据库初始化脚本
-- 智能对话与知识检索系统 (RAG + AI Agent)
-- ============================================================
-- 使用方式:
--   1. 先创建数据库: CREATE DATABASE seislearner;
--   2. 再执行此脚本: \i seislearner.sql
--
-- 前置依赖:
--   - PostgreSQL 14+
--   - pgvector 扩展 (CREATE EXTENSION vector;)
-- ============================================================

-- 启用 pgvector 向量扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ----------------------------------------------------------
-- Agent 表: AI智能体配置
-- ----------------------------------------------------------
CREATE TABLE agent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    name TEXT NOT NULL,                    -- Agent 名称
    description TEXT,                      -- 描述（用户可见）
    system_prompt TEXT,                    -- 系统指令
    model TEXT,                            -- 默认使用的模型
    allowed_tools JSONB,                   -- 允许使用的工具列表
    allowed_kbs JSONB,                     -- 允许访问的知识库
    chat_options JSONB,                    -- 其它配置项（温度、top_p、最大token）
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ----------------------------------------------------------
-- chat_session 表: 对话会话
-- ----------------------------------------------------------
CREATE TABLE chat_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    agent_id UUID REFERENCES agent(id) ON DELETE SET NULL,  -- 绑定的 Agent
    
    title TEXT,                          -- 自动生成的标题
    metadata JSONB,                      -- 扩展（例如输入语言、设备类型）
  
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ----------------------------------------------------------
-- chat_message 表: 聊天消息记录
-- ----------------------------------------------------------
CREATE TABLE chat_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,

    role TEXT NOT NULL,                      -- user / assistant / system / tool
    content TEXT,                            -- 主体内容
    metadata JSONB,                          -- 工具调用、RAG 片段、模型参数等
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ----------------------------------------------------------
-- knowledge_base 表: 知识库
-- ----------------------------------------------------------
CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name TEXT NOT NULL,
    description TEXT,
    metadata JSONB,                         -- 业务属性，如行业/标签

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ----------------------------------------------------------
-- document 表: 文档记录
-- ----------------------------------------------------------
CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,

    filename TEXT NOT NULL,
    filetype TEXT,                          -- pdf / md / txt 等
    size BIGINT,                            -- 文件大小
    metadata JSONB,                         -- 页数、上传方式、解析参数等

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ----------------------------------------------------------
-- chunk_bge_m3 表: 文档向量切片（qwen3-vl-embedding, 1536维多模态）
-- ----------------------------------------------------------
CREATE TABLE chunk_bge_m3 (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,

    content TEXT NOT NULL,                  -- 切片后的文本内容
    metadata JSONB,                         -- 页码、段落号、chunk index 等
    embedding VECTOR(1536) NOT NULL,        -- qwen3-vl-embedding 多模态模型, 1536 维向量

    start_offset INT,                       -- 在原始文档中的起始偏移量
    end_offset INT,                         -- 结束偏移量
    parent_chunk_id UUID,                   -- 父分片ID（用于合并）
    image_references JSONB,                 -- 关联的图片引用（JSON数组）

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 向量索引：HNSW 加速相似度搜索（1536维 < 2000上限，可用索引）
-- m=16, ef_construction=128 适合中小规模数据集
CREATE INDEX idx_chunk_embedding
ON chunk_bge_m3
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 128);

-- 全文检索索引：GIN 加速 tsvector 搜索（稀疏检索/BM25）
-- 使用 'simple' 配置（不进行词干提取，适合中文精确匹配）
CREATE INDEX idx_chunk_content_fts
ON chunk_bge_m3
USING gin (to_tsvector('simple', content));
