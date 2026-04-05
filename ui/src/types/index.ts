export type MessageType = "user" | "assistant" | "system" | "tool";

export interface KnowledgeBase {
  knowledgeBaseId: string;
  name: string;
  description: string;
}

export interface ToolCall {
  id: string;
  type: string;
  name: string;
  arguments: string;
}

export interface ToolResponse {
  id: string;
  name: string;
  responseData: string;
}

export interface ChatMessageVOMetadata {
  toolCalls?: ToolCall[];
  toolResponse?: ToolResponse;
}

export interface ChatMessageVO {
  id: string;
  sessionId: string;
  role: MessageType;
  content: string;
  metadata?: ChatMessageVOMetadata;
}

export type SseMessageType =
  | "AI_GENERATED_CONTENT"
  | "AI_PLANNING"
  | "AI_THINKING"
  | "AI_EXECUTING"
  | "AI_DONE"
  | "RETRIEVAL_STARTED"
  | "RETRIEVAL_CHUNK_SELECTED"
  | "RETRIEVAL_STRATEGY_CHANGED"
  | "RETRIEVAL_MERGING_DECISION"
  | "RETRIEVAL_COMPLETED";

export interface SseMessagePayload {
  message?: ChatMessageVO;
  statusText: string;
  done: boolean;
  retrievalData?: RetrievalVisualizationData;
}

export interface RetrievalVisualizationData {
  event: string;
  details?: {
    chunkCount?: number;
    strategy?: string;
    query?: string;
    confidence?: number;
    depth?: number;
    selectedChunks?: string[];
    mergedCount?: number;
  };
  timestamp: number;
}

export interface SseMessageMetadata {
  chatMessageId: string;
}

export interface SseMessage {
  type: SseMessageType;
  payload: SseMessagePayload;
  metadata: SseMessageMetadata;
}

// Agent 相关类型
export interface RetrievalOptions {
  enableHybridSearch: boolean;
  enableRecursiveSearch: boolean;
  enableRerank: boolean;
  topK: number;
  denseWeight: number;
  sparseWeight: number;
  maxRecursionDepth: number;
  confidenceThreshold: number;
  similarityThreshold: number;
}

export interface ChatOptions {
  temperature: number;
  topP: number;
  messageLength: number;
  retrievalOptions: RetrievalOptions;
}

export interface AgentVO {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  model: string;
  allowedTools: string[];
  allowedKbs: string[];
  chatOptions: ChatOptions;
  createdAt: string;
  updatedAt: string;
}

export interface CreateAgentRequest {
  name: string;
  description: string;
  systemPrompt: string;
  model: string;
  allowedTools: string[];
  allowedKbs: string[];
  chatOptions: ChatOptions;
}

export const DEFAULT_RETRIEVAL_OPTIONS: RetrievalOptions = {
  enableHybridSearch: true,
  enableRecursiveSearch: true,
  enableRerank: true,
  topK: 5,
  denseWeight: 0.7,
  sparseWeight: 0.3,
  maxRecursionDepth: 4,
  confidenceThreshold: 0.5,
  similarityThreshold: 0.3,
};

export const DEFAULT_CHAT_OPTIONS: ChatOptions = {
  temperature: 0.7,
  topP: 1.0,
  messageLength: 10,
  retrievalOptions: DEFAULT_RETRIEVAL_OPTIONS,
};
