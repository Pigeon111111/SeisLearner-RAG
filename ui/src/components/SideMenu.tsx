import React, { useState } from "react";
import { RobotOutlined, BarChartOutlined } from "@ant-design/icons";
import { Tabs, type TabsProps } from "antd";
import { useNavigate } from "react-router-dom";
import AgentTabContent from "./tabs/AgentTabContent.tsx";
import AddAgentModal from "./modals/AddAgentModal.tsx";
import ChatTabContent from "./tabs/ChatTabContent.tsx";
import KnowledgeBaseTabContent from "./tabs/KnowledgeBaseTabContent.tsx";
import AddKnowledgeBaseModal from "./modals/AddKnowledgeBaseModal.tsx";
import { useAgents } from "../hooks/useAgents.ts";
import { useKnowledgeBases } from "../hooks/useKnowledgeBases.ts";

interface SideMenuProps {
  children?: React.ReactNode;
}

const SideMenu: React.FC<SideMenuProps> = () => {
  const navigate = useNavigate();

  const [isAddAgentModalOpen, setIsAddAgentModalOpen] = useState(false);
  const toggleAddAgentModal = () => {
    setIsAddAgentModalOpen(!isAddAgentModalOpen);
    setEditingAgent(null);
  };

  const [editingAgent, setEditingAgent] = useState<
    import("../api/api.ts").AgentVO | null
  >(null);

  const [isAddKnowledgeBaseModalOpen, setIsAddKnowledgeBaseModalOpen] =
    useState(false);
  const toggleAddKnowledgeBaseModal = () => {
    setIsAddKnowledgeBaseModalOpen(!isAddKnowledgeBaseModalOpen);
  };
  const { agents, createAgentHandle, deleteAgentHandle, updateAgentHandle } =
    useAgents();

  const [activeKey, setActiveKey] = useState(() => {
    if (location.pathname.startsWith("/agent")) return "agent";
    if (location.pathname.startsWith("/knowledge-base")) return "knowledgeBase";
    if (location.pathname.startsWith("/chat")) return "chat";
    if (location.pathname.startsWith("/evaluation")) return "evaluation";
    return "agent";
  });

  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();

  const handleTabChange = (key: string) => {
    setActiveKey(key);
    if (key === "evaluation") {
      navigate("/evaluation");
    }
  };

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: <span className="select-none text-sm">智能体助手</span>,
      children: (
        <AgentTabContent
          agents={agents}
          onSelectAgent={() => {}}
          onCreateAgentClick={toggleAddAgentModal}
          onEditAgent={(agent) => {
            setEditingAgent(agent);
            setIsAddAgentModalOpen(true);
          }}
          onDeleteAgent={deleteAgentHandle}
        />
      ),
    },
    {
      key: "chat",
      label: <span className="select-none text-sm">聊天记录</span>,
      children: <ChatTabContent />,
    },
    {
      key: "knowledgeBase",
      label: <span className="select-none text-sm">知识库</span>,
      children: (
        <KnowledgeBaseTabContent
          knowledgeBases={knowledgeBases}
          onCreateKnowledgeBaseClick={toggleAddKnowledgeBaseModal}
          onSelectKnowledgeBase={(knowledgeBaseId) => {
            navigate(`/knowledge-base/${knowledgeBaseId}`);
          }}
        />
      ),
    },
    {
      key: "evaluation",
      label: <span className="select-none text-sm">评估系统</span>,
      children: (
        <div className="p-4">
          <div 
            className="flex items-center gap-3 p-4 bg-gradient-to-r from-indigo-50 to-purple-50 rounded-lg cursor-pointer hover:from-indigo-100 hover:to-purple-100 transition-colors"
            onClick={() => navigate("/evaluation")}
          >
            <BarChartOutlined className="text-2xl text-indigo-600" />
            <div>
              <div className="font-medium text-gray-800">RAGAS 评估系统</div>
              <div className="text-xs text-gray-500">评估检索和生成质量</div>
            </div>
          </div>
        </div>
      ),
    },
  ];

  return (
    <div className="flex flex-col h-full">
      <div className="h-12 w-full flex items-center border-b border-gray-200 px-3 shrink-0">
        <div className="flex items-center gap-2">
          <RobotOutlined className="text-lg text-indigo-600" />
          <div className="text-base font-semibold select-none text-gray-900">
            seislearner
          </div>
        </div>
      </div>
      <div className="flex-1 min-h-0 overflow-hidden">
        <Tabs
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
          className="h-full [&_.ant-tabs-nav]:!mb-0"
        />
      </div>
      <AddAgentModal
        open={isAddAgentModalOpen}
        onClose={toggleAddAgentModal}
        createAgentHandle={createAgentHandle}
        updateAgentHandle={updateAgentHandle}
        editingAgent={editingAgent}
      />
      <AddKnowledgeBaseModal
        open={isAddKnowledgeBaseModalOpen}
        onClose={toggleAddKnowledgeBaseModal}
        createKnowledgeBaseHandle={createKnowledgeBaseHandle}
      />
    </div>
  );
};

export default SideMenu;
