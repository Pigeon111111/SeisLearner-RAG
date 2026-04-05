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

const ToolCallDisplay: React.FC<{ toolCall: ToolCall }> = ({ toolCall }) => {
  let parsedArgs: Record<string, unknown> = {};
  try {
    parsedArgs = JSON.parse(toolCall.arguments) as Record<string, unknown>;
  } catch {
    // 解析失败使用原始字符串
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

interface ParsedCitation {
  index: number;
  source: string;
  relevance: string;
  position?: string;
  content: string;
}

function parseRetrievalResult(rawText: string): {
  summary: string;
  citations: ParsedCitation[];
  tail: string;
} | null {
  const summaryMatch = rawText.match(/检索完成[^\n]*/);
  if (!summaryMatch) return null;

  const summary = summaryMatch[0];
  const citations: ParsedCitation[] = [];
  let remaining = rawText.slice(summaryMatch.index! + summaryMatch[0].length);

  const citationRegex = /【引用\s*(\d+)】[（(]([^,，)]+)[,，]\s*相关度\s*([\d.]+%)[）)]\n([\s\S]*?)(?=【引用\s*\d+】[（(]|【回答要求】|$)/g;

  let match: RegExpExecArray | null;
  while ((match = citationRegex.exec(remaining)) !== null) {
    const index = parseInt(match[1], 10);
    const source = match[2].trim();
    const relevance = match[3].trim();
    let body = match[4].trim();

    let position: string | undefined;
    const posMatch = body.match(/位置[：:]\s*(.+)/);
    if (posMatch) {
      position = posMatch[1].trim();
      body = body.slice(posMatch.index! + posMatch[0].length).trim();
    }

    citations.push({ index, source, relevance, position, content: body });
  }

  const tailMatch = remaining.match(/【回答要求】[\s\S]*/);
  const tail = tailMatch ? tailMatch[0].trim() : "";

  return citations.length > 0 ? { summary, citations, tail } : null;
}

const ToolResponseDisplay: React.FC<{ toolResponse: ToolResponse }> = ({
  toolResponse,
}) => {
  const [expanded, setExpanded] = useState(false);

  const rawText = toolResponse.responseData || "";

  const isKnowledgeTool = toolResponse.name === "KnowledgeTool";
  const parsed = isKnowledgeTool ? parseRetrievalResult(rawText) : null;

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
              <div className="px-3 py-1.5 bg-blue-50 text-blue-700 font-medium">
                {parsed.summary}
              </div>
              {parsed.citations.map((cite) => (
                <div key={cite.index} className="px-3 py-2">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
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
                  <div className="text-gray-600 pl-7 whitespace-pre-wrap break-words leading-relaxed max-h-64 overflow-y-auto">
                    <XMarkdown
                      streaming={{ enableAnimation: false, hasNextChunk: true }}
                      components={{
                        img: ({ src, alt, ...imgProps }: { src?: string; alt?: string; [key: string]: unknown }) => (
                          <img 
                            src={src || ''} 
                            alt={alt || ''} 
                            className="max-w-full h-auto rounded-lg my-1 cursor-pointer hover:opacity-90 transition-opacity max-h-40"
                            loading="lazy"
                            onClick={() => {
                              if (src) window.open(src, '_blank');
                            }}
                            {...imgProps as React.ImgHTMLAttributes<HTMLImageElement>}
                          />
                        ),
                      }}
                    >
                      {cite.content}
                    </XMarkdown>
                  </div>
                </div>
              ))}
              {parsed.tail && (
                <div className="px-3 py-1.5 bg-gray-50 text-gray-400 text-[10px] italic">
                  系统提示已发送给AI模型
                </div>
              )}
            </div>
          ) : (
            <div className="p-2 bg-gray-50 whitespace-pre-wrap break-words text-gray-600">
              {rawText}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

// 公式预处理：将 LaTeX 公式转换为 Markdown 兼容格式
function preprocessLatex(content: string): string {
  // 处理块级公式 $$...$$
  let processed = content.replace(/\$\$([\s\S]*?)\$\$/g, (_match, formula) => {
    const cleaned = formula.trim();
    return `\n$$${cleaned}$$\n`;
  });
  
  // 处理行内公式 $...$（非贪婪，避免匹配金额等）
  processed = processed.replace(/(?<!\$)\$(?!\$)([^\$\n]+?)\$(?!\$)/g, (_match, formula) => {
    const cleaned = formula.trim();
    // 只处理看起来像公式的（包含字母、运算符等）
    if (/[a-zA-Z\\^_{}[\]()]/.test(cleaned)) {
      return `$${cleaned}$`;
    }
    return `$${cleaned}$`;
  });
  
  return processed;
}

// 图片路径处理：将相对路径转换为可访问的URL
function processImagePaths(content: string): string {
  // 处理 Markdown 图片语法 ![alt](path)
  return content.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (match, alt, path) => {
    // 如果是相对路径且不是 URL，添加 API 前缀
    if (!path.startsWith('http') && !path.startsWith('data:')) {
      const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
      const fullPath = path.startsWith('/') ? `${apiBase}${path}` : `${apiBase}/${path}`;
      return `![${alt}](${fullPath})`;
    }
    return match;
  });
}

const AgentChatHistory: React.FC<AgentChatHistoryProps> = ({
  messages,
  displayAgentStatus = false,
  agentStatusText = "",
  agentStatusType,
}) => {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const [isNearBottom, setIsNearBottom] = useState(true);
  const SCROLL_THRESHOLD = 20;
  const prevMessagesLengthRef = useRef(messages.length);

  const checkIfNearBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return false;

    const { scrollTop, clientHeight, scrollHeight } = container;
    const distanceFromBottom = scrollHeight - scrollTop - clientHeight;
    return distanceFromBottom <= SCROLL_THRESHOLD;
  }, []);

  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    requestAnimationFrame(() => {
      if (container) {
        container.scrollTop = container.scrollHeight;
      }
    });
  }, []);

  const handleScroll = useCallback(() => {
    const nearBottom = checkIfNearBottom();
    setIsNearBottom(nearBottom);
  }, [checkIfNearBottom]);

  useEffect(() => {
    const container = scrollContainerRef.current;
    if (!container) return;

    const initTimer = setTimeout(() => {
      setIsNearBottom(checkIfNearBottom());
    }, 0);

    container.addEventListener("scroll", handleScroll, { passive: true });

    return () => {
      clearTimeout(initTimer);
      container.removeEventListener("scroll", handleScroll);
    };
  }, [handleScroll, checkIfNearBottom]);

  useEffect(() => {
    const hasNewMessage = messages.length > prevMessagesLengthRef.current;
    prevMessagesLengthRef.current = messages.length;

    if (hasNewMessage && isNearBottom) {
      scrollToBottom();
    }
  }, [messages, isNearBottom, scrollToBottom]);

  useEffect(() => {
    if (displayAgentStatus && isNearBottom) {
      scrollToBottom();
    }
  }, [displayAgentStatus, isNearBottom, scrollToBottom]);

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

  // 处理消息内容，支持公式和图片
  const processContent = (content: string): string => {
    let processed = preprocessLatex(content);
    processed = processImagePaths(processed);
    return processed;
  };

  return (
    <div 
      ref={scrollContainerRef}
      className="flex-1 px-3 sm:px-4 md:px-6 lg:px-8 xl:px-12 pt-4 overflow-y-scroll"
    >
      {messages.map((message) => {
        return (
          <div className="mb-4" key={message.id}>
            {message.role === "assistant" && (
              <Bubble
                content={
                  <div className="w-full x-md-content">
                    {message.metadata?.toolCalls &&
                      message.metadata.toolCalls.length > 0 && (
                        <div className="mb-2 flex flex-wrap gap-2">
                          {message.metadata.toolCalls.map((toolCall) => (
                            <ToolCallDisplay key={toolCall.id} toolCall={toolCall} />
                          ))}
                        </div>
                      )}
                    {message.content && (
                      <div>
                        <XMarkdown
                          streaming={{ enableAnimation: false, hasNextChunk: true }}
                          components={{
                            // 自定义图片渲染
                            img: ({ src, alt, ...props }: { src?: string; alt?: string; [key: string]: unknown }) => (
                              <img 
                                src={src || ''} 
                                alt={alt || ''} 
                                className="max-w-full h-auto rounded-lg my-2 cursor-pointer hover:opacity-90 transition-opacity"
                                loading="lazy"
                                onClick={() => {
                                  if (src) window.open(src, '_blank');
                                }}
                                {...props as React.ImgHTMLAttributes<HTMLImageElement>}
                              />
                            ),
                          }}
                        >
                          {processContent(message.content)}
                        </XMarkdown>
                      </div>
                    )}
                  </div>
                }
                placement="start"
              />
            )}

            {message.role === "tool" && message.metadata?.toolResponse && (
              <div className="flex justify-start">
                <div className="max-w-[90%]">
                  <ToolResponseDisplay toolResponse={message.metadata.toolResponse} />
                </div>
              </div>
            )}

            {message.role === "user" && (
              <Bubble content={message.content} placement="end" />
            )}

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
                    {getStatusLabel()}
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
