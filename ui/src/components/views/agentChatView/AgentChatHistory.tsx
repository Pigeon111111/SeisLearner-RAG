import React, { useState, useRef, useEffect, useCallback } from "react";
import { Bubble } from "@ant-design/x";
import XMarkdown from "@ant-design/x-markdown";
import {
  ToolOutlined,
  CheckCircleOutlined,
  RobotOutlined,
  DownOutlined,
  RightOutlined,
} from "@ant-design/icons";
import type { ChatMessageVO, SseMessageType, ToolCall, ToolResponse } from "../../../types";

interface AgentChatHistoryProps {
  messages: ChatMessageVO[];
  displayAgentStatus?: boolean;
  agentStatusText?: string;
  agentStatusType?: SseMessageType;
}

// 工具调用展示组件（简化版，用于 assistant 消息内）
const ToolCallDisplay: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => {
  let parsedArgs: Record<string, unknown> = {};
  try {
    parsedArgs = JSON.parse(toolCall.arguments) as Record<string, unknown>;
  } catch {
    // 如果解析失败，使用原始字符串
  }

  const argCount = Object.keys(parsedArgs).length;
  const argPreview = argCount > 0 
    ? Object.keys(parsedArgs).slice(0, 2).join(", ") + (argCount > 2 ? "..." : "")
    : toolCall.arguments.slice(0, 50) + (toolCall.arguments.length > 50 ? "..." : "");

  return (
    <div className="text-xs text-gray-500 flex items-center gap-1.5">
      <ToolOutlined className="text-blue-500" />
      <span className="font-mono text-blue-600">{toolCall.name}</span>
      {argPreview && (
        <>
          <span className="text-gray-400">·</span>
          <span className="text-gray-500 truncate max-w-[120px] sm:max-w-[200px]">{argPreview}</span>
        </>
      )}
    </div>
  );
};

// 解析 KnowledgeTool 检索结果，提取结构化引用信息
interface ParsedCitation {
  index: number;
  source: string;       // 来源文件名
  relevance: string;    // 相关度
  position?: string;    // 位置信息
  content: string;      // 内容
}

function parseRetrievalResult(rawText: string): {
  summary: string;
  citations: ParsedCitation[];
  tail: string;
} | null {
  // 匹配检索摘要行
  const summaryMatch = rawText.match(/检索完成[^\n]*/);
  if (!summaryMatch) return null;

  const summary = summaryMatch[0];
  const citations: ParsedCitation[] = [];
  let remaining = rawText.slice(summaryMatch.index! + summaryMatch[0].length);

  // 匹配每条引用块：【引用 N】(来源文件名, 相关度 XX%)
  const citationRegex = /【引用\s*(\d+)】[（(]([^,，)]+)[,，]\s*相关度\s*([\d.]+%)[）)]\n([\s\S]*?)(?=【引用\s*\d+】[（(]|【回答要求】|$)/g;

  let match: RegExpExecArray | null;
  while ((match = citationRegex.exec(remaining)) !== null) {
    const index = parseInt(match[1], 10);
    const source = match[2].trim();
    const relevance = match[3].trim();
    let body = match[4].trim();

    // 提取位置行
    let position: string | undefined;
    const posMatch = body.match(/位置[：:]\s*(.+)/);
    if (posMatch) {
      position = posMatch[1].trim();
      body = body.slice(posMatch.index! + posMatch[0].length).trim();
    }

    citations.push({ index, source, relevance, position, content: body });
  }

  // 提取尾部回答要求
  const tailMatch = remaining.match(/【回答要求】[\s\S]*/);
  const tail = tailMatch ? tailMatch[0].trim() : "";

  return citations.length > 0 ? { summary, citations, tail } : null;
}

// 工具响应展示组件（可折叠，支持结构化展示检索结果）
const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);

  // 原始响应文本
  const rawText = toolResponse.responseData || "";

  // 尝试解析为结构化检索结果
  const isKnowledgeTool = toolResponse.name === "KnowledgeTool";
  const parsed = isKnowledgeTool ? parseRetrievalResult(rawText) : null;

  // 预览文本
  const previewText = parsed
    ? `${parsed.summary} · ${parsed.citations.length}条引用`
    : rawText.length > 150
      ? rawText.slice(0, 150) + "..."
      : rawText;

  return (
    <div className="my-1.5 text-xs">
      <div
        className="flex items-center gap-2 text-gray-500 cursor-pointer hover:text-gray-700 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {expanded ? (
          <DownOutlined className="text-gray-400" />
        ) : (
          <RightOutlined className="text-gray-400" />
        )}
        <CheckCircleOutlined className="text-green-500" />
        <span className="font-mono text-green-600">{toolResponse.name}</span>
        <span className="text-gray-400">·</span>
        <span className="text-gray-500 truncate flex-1">{previewText}</span>
      </div>
      {expanded && (
        <div className="ml-5 mt-1.5 rounded border border-gray-200 max-h-96 overflow-y-auto">
          {parsed ? (
            <div className="divide-y divide-gray-100">
              {/* 摘要行 */}
              <div className="px-3 py-1.5 bg-blue-50 text-blue-700 font-medium">
                {parsed.summary}
              </div>
              {/* 引用列表 */}
              {parsed.citations.map((cite) => (
                <div key={cite.index} className="px-3 py-2">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="inline-flex items-center justify-center w-5 h-5 rounded-full bg-blue-100 text-blue-700 text-[10px] font-semibold">
                      {cite.index}
                    </span>
                    <span className="text-gray-800 font-medium truncate max-w-[120px] sm:max-w-[200px]">
                      {cite.source}
                    </span>
                    <span className="text-gray-400">·</span>
                    <span className="text-gray-500">{cite.relevance}</span>
                    {cite.position && (
                      <>
                        <span className="text-gray-300">|</span>
                        <span className="text-gray-400">{cite.position}</span>
                      </>
                    )}
                  </div>
                  <div className="text-gray-600 pl-7 whitespace-pre-wrap break-words leading-relaxed max-h-32 overflow-y-auto">
                    {cite.content}
                  </div>
                </div>
              ))}
              {/* 尾部提示（折叠显示） */}
              {parsed.tail && (
                <div className="px-3 py-1.5 bg-gray-50 text-gray-400 text-[10px] italic">
                  系统提示已发送给AI模型
                </div>
              )}
            </div>
          ) : (
            /* 非检索结果：直接显示原始文本 */
            <div className="p-2 bg-gray-50 whitespace-pre-wrap break-words text-gray-600">
              {rawText}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
}) => {
  // 滚动容器引用
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  // 是否允许自动滚动（用户是否接近底部）
  const [isNearBottom, setIsNearBottom] = useState(true);
  // 容错阈值（像素）
  const SCROLL_THRESHOLD = 20;
  // 上一次消息数量，用于检测新消息
  const prevMessagesLengthRef = useRef(messages.length);

  // 检查是否接近底部
  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;

    const { scrollTop, clientHeight, scrollHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    return distanceFromBottom <= SCROLL_THRESHOLD;
  }, []);

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 使用 requestAnimationFrame 确保 DOM 更新完成后再滚动
    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  // 处理滚动事件，实时更新是否接近底部的状态
  const handleScroll = useCallback(() => {
    const nearBottom = checkIfNearBottom();
    setIsNearBottom(nearBottom);
  }, [checkIfNearBottom]);

  // 监听滚动事件
  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    // 初始化时检查是否在底部（延迟执行以避免同步 setState）
    const initTimer = setTimeout(() => {
      setIsNearBottom(checkIfNearBottom());
    }, 0);

    container.addEventListener("scroll", handleScroll, { passive: true });

    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  // 监听消息变化，决定是否自动滚动
  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;

    // 如果有新消息且用户接近底部，则自动滚动
    if (hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [messages, isNearBottom, scrollToBottom]);

  // 当 displayAgentStatus 变化时，如果用户接近底部，也自动滚动
  useEffect(() => {
    if (displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

  // 获取状态标签
  const getStatusLabel = () => {
    switch (agentStatusType) {
      case "AI_PLANNING":
        return "规划中";
      case "AI_THINKING":
        return "思考中";
      case "AI_EXECUTING":
        return "执行中";
      default:
        return "处理中";
    }
  };

  return (
    <div 
      ref={scrollContainerRef}
      className="flex-1 px-4 sm:px-6 lg:px-16 pt-4 overflow-y-scroll"
    >
      {messages.map((message) => {
        return (
          <div className="mb-4" key={message.id}>
            {/* Assistant 消息 */}
            {message.role === "assistant" && (
              <Bubble
                content={
                  <div className="w-full">
                    {/* 工具调用展示 */}
                    {message.metadata?.toolCalls &&
                      message.metadata.toolCalls.length > 0 && (
                        <div className="mb-2 flex flex-wrap gap-2">
                          {message.metadata.toolCalls.map((toolCall) => (
                            <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
                          ))}
                        </div>
                      )}
                    {/* 消息内容 */}
                    {message.content && (
                      <div>
                        <XMarkdown
                          streaming={{ enableAnimation: false, hasNextChunk: true }}
                        >
                          {message.content}
                        </XMarkdown>
                      </div>
                    )}
                  </div>
                }
                placement="start"
              />
            )}

            {/* Tool 消息 - 简洁展示，不使用气泡 */}
            {message.role === "tool" && message.metadata?.toolResponse && (
              <div className="flex justify-start">
                <div className="max-w-[85%] sm:max-w-[85%] lg:max-w-[85%]">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            )}

            {/* User 消息 */}
            {message.role === "user" && (
              <Bubble content={message.content} placement="end" />
            )}

            {/* System 消息 */}
            {message.role === "system" && (
              <div className="flex justify-center">
                <div className="px-3 py-1 bg-gray-100 text-gray-600 text-xs rounded-full flex items-center gap-1">
                  <RobotOutlined />
                  <span>{message.content}</span>
                </div>
              </div>
            )}
          </div>
        );
      })}
      {displayAgentStatus && (
        <div className="mb-3">
          <div
            className="animate-pulse"
            style={{
              animation: "pulse 0.8s cubic-bezier(0.4, 0, 0.6, 1) infinite",
              filter: "brightness(1.15)",
            }}
          >
            <Bubble
              content={
                <span className="flex items-center gap-2">
                  <span
                    className="font-semibold text-blue-600"
                    style={{
                      animation:
                        "pulse 0.7s cubic-bezier(0.4, 0, 0.6, 1) infinite",
                      textShadow:
                        "0 0 10px rgba(37, 99, 235, 1), 0 0 20px rgba(37, 99, 235, 0.8), 0 0 30px rgba(37, 99, 235, 0.5)",
                      filter: "brightness(1.3)",
                    }}
                  >
                    ✨ {getStatusLabel()}
                  </span>
                  <span className="text-gray-400">·</span>
                  <span className="text-gray-600">{agentStatusText}</span>
                </span>
              }
              placement="start"
            />
          </div>
        </div>
      )}
    </div>
  );
};

export default AgentChatHistory;
