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
