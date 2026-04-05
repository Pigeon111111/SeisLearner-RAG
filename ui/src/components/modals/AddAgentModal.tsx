import React, { useEffect, useState } from "react";
import { Button, Checkbox, Input, Modal, Select, Slider, Switch, Divider, Tooltip } from "antd";
import TextArea from "antd/es/input/TextArea";
import { SaveOutlined, QuestionCircleOutlined } from "@ant-design/icons";
import {
  type CreateAgentRequest,
  type UpdateAgentRequest,
  type AgentVO,
  type ModelType,
  getOptionalTools,
  type ToolVO,
} from "../../api/api.ts";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { DEFAULT_RETRIEVAL_OPTIONS, type RetrievalOptions } from "../../types/index.ts";

interface AddAgentModalProps {
  open: boolean;
  onClose: () => void;
  createAgentHandle: (request: CreateAgentRequest) => Promise<void>;
  updateAgentHandle?: (
    agentId: string,
    request: UpdateAgentRequest,
  ) => Promise<void>;
  editingAgent?: AgentVO | null;
}

const menuItems = [
  { key: "base", label: "基础设置" },
  { key: "model", label: "模型设置" },
  { key: "knowledge", label: "知识库设置" },
  { key: "retrieval", label: "检索设置" },
  { key: "tools", label: "工具调用" },
];

const AddAgentModal: React.FC<AddAgentModalProps> = ({
  open,
  onClose,
  createAgentHandle,
  updateAgentHandle,
  editingAgent,
}) => {
  const [selectedKey, setSelectedKey] = useState<string>("base");
  const { knowledgeBases } = useKnowledgeBases();
  const [tools, setTools] = useState<ToolVO[]>([]);

  const [formData, setFormData] = useState<CreateAgentRequest>({
    name: "智能体助手",
    description: "",
    systemPrompt: "你是一个很有用的智能体助手",
    model: "deepseek-chat",
    allowedTools: [],
    allowedKbs: [],
    chatOptions: {
      temperature: 0.7,
      topP: 1.0,
      messageLength: 20,
      retrievalOptions: DEFAULT_RETRIEVAL_OPTIONS,
    },
  });

  const [createAgentLoading, setCreateAgentLoading] = useState(false);

  const retrievalOptions = formData.chatOptions?.retrievalOptions || DEFAULT_RETRIEVAL_OPTIONS;

  const updateRetrievalOptions = (updates: Partial<RetrievalOptions>) => {
    setFormData({
      ...formData,
      chatOptions: {
        ...formData.chatOptions,
        retrievalOptions: {
          ...retrievalOptions,
          ...updates,
        },
      },
    });
  };

  useEffect(() => {
    if (editingAgent) {
      setFormData({
        name: editingAgent.name,
        description: editingAgent.description || "",
        systemPrompt: editingAgent.systemPrompt || "",
        model: editingAgent.model,
        allowedTools: editingAgent.allowedTools || [],
        allowedKbs: editingAgent.allowedKbs || [],
        chatOptions: {
          temperature: editingAgent.chatOptions?.temperature || 0.7,
          topP: editingAgent.chatOptions?.topP || 1.0,
          messageLength: editingAgent.chatOptions?.messageLength || 10,
          retrievalOptions: editingAgent.chatOptions?.retrievalOptions || DEFAULT_RETRIEVAL_OPTIONS,
        },
      });
    } else {
      setFormData({
        name: "智能体助手",
        description: "基于知识库的RAG问答助手",
        systemPrompt: "你是一个专业的AI知识库问答助手。请根据检索到的知识库内容回答用户问题。\n\n【回答要求】\n- 优先使用知识库中的信息进行回答\n- 如果知识库中没有相关信息，明确告知用户\n- 回答要简洁准确，引用来源\n- 支持中英文混合回答",
        model: "deepseek-chat",
        allowedTools: ["KnowledgeTool"],
        allowedKbs: [],
        chatOptions: {
          temperature: 0.7,
          topP: 1.0,
          messageLength: 20,
          retrievalOptions: DEFAULT_RETRIEVAL_OPTIONS,
        },
      });
    }
  }, [editingAgent, open]);

  useEffect(() => {
    async function fetchTools() {
      try {
        const resp = await getOptionalTools();
        setTools(resp.tools);
      } catch (error) {
        console.error("获取工具列表失败:", error);
      }
    }
    fetchTools().then();
  }, []);

  const isEditMode = !!editingAgent;

  const renderRetrievalSettings = () => (
    <div className="space-y-4">
      <div className="mb-4">
        <label className="block text-gray-700 font-medium mb-3">检索功能开关</label>
        <div className="space-y-3">
          <div className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
            <div className="flex items-center gap-2">
              <span className="text-gray-700">混合检索</span>
              <Tooltip title="同时使用稠密向量检索和稀疏全文检索，提高召回率">
                <QuestionCircleOutlined className="text-gray-400" />
              </Tooltip>
            </div>
            <Switch
              checked={retrievalOptions.enableHybridSearch}
              onChange={(checked) => updateRetrievalOptions({ enableHybridSearch: checked })}
            />
          </div>
          
          <div className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
            <div className="flex items-center gap-2">
              <span className="text-gray-700">递归检索</span>
              <Tooltip title="当首轮检索结果不理想时，自动使用StepBack、HyDE等策略进行多轮检索">
                <QuestionCircleOutlined className="text-gray-400" />
              </Tooltip>
            </div>
            <Switch
              checked={retrievalOptions.enableRecursiveSearch}
              onChange={(checked) => updateRetrievalOptions({ enableRecursiveSearch: checked })}
            />
          </div>
          
          <div className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
            <div className="flex items-center gap-2">
              <span className="text-gray-700">Rerank精排</span>
              <Tooltip title="使用重排序模型对检索结果进行精细化排序，提高相关性">
                <QuestionCircleOutlined className="text-gray-400" />
              </Tooltip>
            </div>
            <Switch
              checked={retrievalOptions.enableRerank}
              onChange={(checked) => updateRetrievalOptions({ enableRerank: checked })}
            />
          </div>
        </div>
      </div>

      <Divider />

      <div className="mb-4">
        <label className="block text-gray-700 font-medium mb-3">检索参数</label>
        <div className="space-y-4">
          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">Top K（检索数量）</label>
                <Tooltip title="每次检索返回的最大文档片段数量">
                  <QuestionCircleOutlined className="text-gray-400" />
                </Tooltip>
              </div>
              <span className="text-sm font-medium text-gray-700">{retrievalOptions.topK}</span>
            </div>
            <Slider
              min={1}
              max={20}
              value={retrievalOptions.topK}
              onChange={(value) => updateRetrievalOptions({ topK: value })}
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">相似度阈值</label>
                <Tooltip title="只返回相似度高于此阈值的结果">
                  <QuestionCircleOutlined className="text-gray-400" />
                </Tooltip>
              </div>
              <span className="text-sm font-medium text-gray-700">{(retrievalOptions.similarityThreshold * 100).toFixed(0)}%</span>
            </div>
            <Slider
              min={0}
              max={100}
              value={retrievalOptions.similarityThreshold * 100}
              onChange={(value) => updateRetrievalOptions({ similarityThreshold: value / 100 })}
            />
          </div>
        </div>
      </div>

      <Divider />

      <div className="mb-4">
        <label className="block text-gray-700 font-medium mb-3">混合检索权重</label>
        <div className="space-y-4">
          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">稠密向量权重</label>
                <Tooltip title="语义向量检索的权重占比">
                  <QuestionCircleOutlined className="text-gray-400" />
                </Tooltip>
              </div>
              <span className="text-sm font-medium text-gray-700">{(retrievalOptions.denseWeight * 100).toFixed(0)}%</span>
            </div>
            <Slider
              min={0}
              max={100}
              value={retrievalOptions.denseWeight * 100}
              onChange={(value) => {
                updateRetrievalOptions({
                  denseWeight: value / 100,
                  sparseWeight: 1 - value / 100,
                });
              }}
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">稀疏向量权重</label>
                <Tooltip title="关键词全文检索的权重占比">
                  <QuestionCircleOutlined className="text-gray-400" />
                </Tooltip>
              </div>
              <span className="text-sm font-medium text-gray-700">{(retrievalOptions.sparseWeight * 100).toFixed(0)}%</span>
            </div>
            <Slider
              min={0}
              max={100}
              value={retrievalOptions.sparseWeight * 100}
              onChange={(value) => {
                updateRetrievalOptions({
                  sparseWeight: value / 100,
                  denseWeight: 1 - value / 100,
                });
              }}
            />
          </div>
        </div>
      </div>

      <Divider />

      <div className="mb-4">
        <label className="block text-gray-700 font-medium mb-3">递归检索参数</label>
        <div className="space-y-4">
          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">最大递归深度</label>
                <Tooltip title="递归检索的最大轮次">
                  <QuestionCircleOutlined className="text-gray-400" />
                </Tooltip>
              </div>
              <span className="text-sm font-medium text-gray-700">{retrievalOptions.maxRecursionDepth}</span>
            </div>
            <Slider
              min={1}
              max={6}
              value={retrievalOptions.maxRecursionDepth}
              onChange={(value) => updateRetrievalOptions({ maxRecursionDepth: value })}
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <label className="text-sm text-gray-600">置信度阈值</label>
                <Tooltip title="首轮检索结果置信度低于此阈值时触发递归检索">
                  <QuestionCircleOutlined className="text-gray-400" />
                </Tooltip>
              </div>
              <span className="text-sm font-medium text-gray-700">{(retrievalOptions.confidenceThreshold * 100).toFixed(0)}%</span>
            </div>
            <Slider
              min={0}
              max={100}
              value={retrievalOptions.confidenceThreshold * 100}
              onChange={(value) => updateRetrievalOptions({ confidenceThreshold: value / 100 })}
            />
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={isEditMode ? "编辑智能体" : "智能体助手"}
      footer={null}
      width={800}
      centered
    >
      <div className="flex h-[500px]">
        <div className="w-[150px] h-full border-r border-gray-200 pr-2">
          <div className="flex flex-col gap-0.5 select-none cursor-pointer">
            {menuItems.map((item) => {
              const isSelected = selectedKey === item.key;
              return (
                <div
                  key={item.key}
                  onClick={() => setSelectedKey(item.key)}
                  className={`px-3 py-2 rounded-lg hover:bg-gray-100 ${isSelected ? "bg-gray-100 text-gray-900 font-medium" : "text-gray-600"}`}
                >
                  {item.label}
                </div>
              );
            })}
          </div>
        </div>
        <div className="flex-1 h-full relative">
          <div className="px-4 pb-4 overflow-y-scroll h-full">
            {selectedKey === "base" && (
              <div>
                <div className="mb-3">
                  <label className="block text-gray-700 font-medium mb-1">名称</label>
                  <Input
                    placeholder="请输入智能体名称"
                    value={formData.name}
                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  />
                </div>
                <div className="mb-3">
                  <label className="block text-gray-700 font-medium mb-1">描述</label>
                  <TextArea
                    placeholder="请输入智能体描述"
                    rows={2}
                    value={formData.description}
                    onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  />
                </div>
                <div className="mb-3">
                  <label className="block text-gray-700 font-medium mb-1">提示词</label>
                  <TextArea
                    placeholder="默认提示词"
                    rows={11}
                    value={formData.systemPrompt}
                    onChange={(e) => setFormData({ ...formData, systemPrompt: e.target.value })}
                  />
                </div>
              </div>
            )}

            {selectedKey === "model" && (
              <div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-1">选择模型</label>
                  <Select
                    options={[
                      { value: "deepseek-chat", label: "deepseek-chat" },
                      { value: "glm-4.6", label: "glm-4.6" },
                    ]}
                    placeholder="请选择模型"
                    style={{ width: "300px" }}
                    value={formData.model}
                    onChange={(value: ModelType) => setFormData({ ...formData, model: value })}
                  />
                </div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-2">模型参数</label>
                  <div className="space-y-4">
                    <div>
                      <div className="flex items-center justify-between mb-2">
                        <label className="text-sm text-gray-600">
                          Temperature（温度）
                          <span className="text-gray-400 ml-1 text-xs">(0.0 - 2.0)</span>
                        </label>
                        <span className="text-sm font-medium text-gray-700 min-w-[40px] text-right">
                          {formData?.chatOptions?.temperature?.toFixed(1)}
                        </span>
                      </div>
                      <Slider
                        min={0}
                        max={2}
                        step={0.1}
                        value={formData?.chatOptions?.temperature}
                        onChange={(value) =>
                          setFormData({
                            ...formData,
                            chatOptions: { ...formData.chatOptions, temperature: value },
                          })
                        }
                      />
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-2">
                        <label className="text-sm text-gray-600">
                          Top P（核采样）
                          <span className="text-gray-400 ml-1 text-xs">(0.0 - 1.0)</span>
                        </label>
                        <span className="text-sm font-medium text-gray-700 min-w-[40px] text-right">
                          {formData?.chatOptions?.topP?.toFixed(1)}
                        </span>
                      </div>
                      <Slider
                        min={0}
                        max={1}
                        step={0.1}
                        value={formData?.chatOptions?.topP}
                        onChange={(value) =>
                          setFormData({
                            ...formData,
                            chatOptions: { ...formData.chatOptions, topP: value },
                          })
                        }
                      />
                    </div>
                    <div>
                      <div className="flex items-center justify-between mb-2">
                        <label className="text-sm text-gray-600">
                          消息窗口长度
                          <span className="text-gray-400 ml-1 text-xs">(1 - 100)</span>
                        </label>
                        <span className="text-sm font-medium text-gray-700 min-w-[40px] text-right">
                          {formData?.chatOptions?.messageLength}
                        </span>
                      </div>
                      <Slider
                        min={1}
                        max={100}
                        step={1}
                        value={formData?.chatOptions?.messageLength}
                        onChange={(value) =>
                          setFormData({
                            ...formData,
                            chatOptions: { ...formData.chatOptions, messageLength: value },
                          })
                        }
                      />
                    </div>
                  </div>
                </div>
              </div>
            )}

            {selectedKey === "knowledge" && (
              <div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-3">知识库</label>
                  <p className="text-sm text-gray-500 mb-4">
                    选择智能体可以访问的知识库，支持多选（最多10个）
                  </p>
                  {knowledgeBases.length === 0 ? (
                    <div className="text-center py-8 text-gray-500">
                      <p>暂无知识库，请先创建知识库</p>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {knowledgeBases.map((kb) => {
                        const kbId = kb.knowledgeBaseId;
                        const isSelected = formData.allowedKbs?.includes(kbId);
                        return (
                          <div
                            key={kbId}
                            className={`border rounded-lg p-4 cursor-pointer transition-all hover:border-blue-400 hover:bg-blue-50 ${
                              isSelected ? "border-blue-500 bg-blue-50" : "border-gray-200"
                            }`}
                            onClick={() => {
                              const currentKbs = formData.allowedKbs || [];
                              if (isSelected) {
                                setFormData({
                                  ...formData,
                                  allowedKbs: currentKbs.filter((k) => k !== kbId),
                                });
                              } else {
                                if (currentKbs.length >= 10) return;
                                setFormData({
                                  ...formData,
                                  allowedKbs: [...currentKbs, kbId],
                                });
                              }
                            }}
                          >
                            <div className="flex items-start gap-2">
                              <Checkbox
                                checked={isSelected}
                                onChange={(e) => {
                                  e.stopPropagation();
                                  const currentKbs = formData.allowedKbs || [];
                                  if (e.target.checked) {
                                    if (currentKbs.length >= 10) return;
                                    setFormData({
                                      ...formData,
                                      allowedKbs: [...currentKbs, kbId],
                                    });
                                  } else {
                                    setFormData({
                                      ...formData,
                                      allowedKbs: currentKbs.filter((k) => k !== kbId),
                                    });
                                  }
                                }}
                                className="mr-3"
                              />
                              <div className="flex-1">
                                <div className="flex items-center mb-1">
                                  <span className="font-medium text-gray-900">{kb.name}</span>
                                </div>
                                {kb.description && (
                                  <p className="text-sm text-gray-600">{kb.description}</p>
                                )}
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>
            )}

            {selectedKey === "retrieval" && renderRetrievalSettings()}

            {selectedKey === "tools" && (
              <div>
                <div className="mb-4">
                  <label className="block text-gray-700 font-medium mb-3">工具调用</label>
                  <p className="text-sm text-gray-500 mb-4">
                    选择智能体可以使用的工具，支持多选
                  </p>
                  {tools.length === 0 ? (
                    <div className="text-center py-8 text-gray-500">
                      <p>暂无可用工具</p>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {tools.map((tool) => {
                        const toolId = tool.name;
                        const isSelected = formData.allowedTools?.includes(toolId);
                        return (
                          <div
                            key={toolId}
                            className={`border rounded-lg p-4 cursor-pointer transition-all hover:border-blue-400 hover:bg-blue-50 ${
                              isSelected ? "border-blue-500 bg-blue-50" : "border-gray-200"
                            }`}
                            onClick={() => {
                              const currentTools = formData.allowedTools || [];
                              if (isSelected) {
                                setFormData({
                                  ...formData,
                                  allowedTools: currentTools.filter((t) => t !== toolId),
                                });
                              } else {
                                setFormData({
                                  ...formData,
                                  allowedTools: [...currentTools, toolId],
                                });
                              }
                            }}
                          >
                            <div className="flex items-start gap-2">
                              <Checkbox
                                checked={isSelected}
                                onChange={(e) => {
                                  e.stopPropagation();
                                  const currentTools = formData.allowedTools || [];
                                  if (e.target.checked) {
                                    setFormData({
                                      ...formData,
                                      allowedTools: [...currentTools, toolId],
                                    });
                                  } else {
                                    setFormData({
                                      ...formData,
                                      allowedTools: currentTools.filter((t) => t !== toolId),
                                    });
                                  }
                                }}
                                className="mr-3"
                              />
                              <div className="flex-1">
                                <div className="flex items-center mb-1">
                                  <span className="font-medium text-gray-900">{tool.name}</span>
                                </div>
                                <p className="text-sm text-gray-600">{tool.description}</p>
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
          <div className="absolute bottom-0 right-0">
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={createAgentLoading}
              onClick={async () => {
                setCreateAgentLoading(true);
                try {
                  if (isEditMode && editingAgent && updateAgentHandle) {
                    await updateAgentHandle(editingAgent.id, formData);
                  } else {
                    await createAgentHandle(formData);
                  }
                  onClose();
                } finally {
                  setCreateAgentLoading(false);
                }
              }}
            >
              {isEditMode ? "更新" : "保存"}
            </Button>
          </div>
        </div>
      </div>
    </Modal>
  );
};

export default AddAgentModal;
