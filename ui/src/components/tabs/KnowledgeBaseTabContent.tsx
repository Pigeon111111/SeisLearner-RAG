import React, { useMemo } from "react";
import { Button, Divider } from "antd";
import { PlusOutlined, BookOutlined } from "@ant-design/icons";
import type { KnowledgeBase } from "../../types";
import { getKnowledgeBaseEmoji } from "../../utils";

interface KnowledgeBaseTabContentProps {
  knowledgeBases: KnowledgeBase[];
  onCreateKnowledgeBaseClick?: () => void;
  onSelectKnowledgeBase?: (knowledgeBaseId: string) => void;
}

const KnowledgeBaseTabContent: React.FC<KnowledgeBaseTabContentProps> = ({
  knowledgeBases,
  onCreateKnowledgeBaseClick,
  onSelectKnowledgeBase,
}) => {
  // 为每个知识库生成 emoji
  const knowledgeBasesWithEmoji = useMemo(() => {
    return knowledgeBases.map((kb) => ({
      ...kb,
      emoji: getKnowledgeBaseEmoji(kb.knowledgeBaseId),
    }));
  }, [knowledgeBases]);

  return (
    <div className="flex flex-col h-full">
      <Button
        color="geekblue"
        variant="filled"
        icon={<PlusOutlined />}
        onClick={onCreateKnowledgeBaseClick}
        className="w-full"
      >
        新建知识库
      </Button>
      <Divider />
      <div className="flex-1 overflow-y-scroll rounded-lg">
        {knowledgeBases.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-gray-400">
            <BookOutlined className="text-4xl mb-2" />
            <p className="text-sm">暂无知识库</p>
            <p className="text-xs mt-1">点击上方按钮创建</p>
          </div>
        ) : (
          <div className="space-y-1.5 p-1.5">
            {knowledgeBasesWithEmoji.map((kb) => (
              <div
                key={kb.knowledgeBaseId}
                onClick={() => onSelectKnowledgeBase?.(kb.knowledgeBaseId)}
                className="w-full px-3 py-2.5 rounded-lg bg-white cursor-pointer transition-all hover:bg-gray-100 hover:shadow-sm"
              >
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center shrink-0 text-lg mt-0.5">
                    {kb.emoji}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="font-medium text-gray-900 truncate">
                      {kb.name}
                    </div>
                    {kb.description && (
                      <div className="text-xs text-gray-500 mt-1 line-clamp-2">
                        {kb.description}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export default KnowledgeBaseTabContent;
