import React, { useState } from "react";
import { RobotOutlined } from "@ant-design/icons";
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

  /**
   * 添加知识库模态框状态
   */
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
    return "agent";
  });

  const { knowledgeBases, createKnowledgeBaseHandle } = useKnowledgeBases();

  // 处理标签页切换
  const handleTabChange = (key: string) => {
    setActiveKey(key);
  };

  const items: TabsProps["items"] = [
    {
      key: "agent",
      label: <span className="select-none">智能体助手</span>,
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
      label: <span className="select-none">聊天记录</span>,
      children: <ChatTabContent />,
    },
    {
      key: "knowledgeBase",
      label: <span className="select-none">知识库</span>,
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
  ];

  return (
    <div className="px-4 flex flex-col h-full">
      <div className="h-14 w-full flex items-center border-b border-gray-200">
        <div className="flex items-center gap-2.5 mx-4">
          <RobotOutlined className="text-xl text-indigo-600" />
          <div className="text-lg font-semibold select-none text-gray-900">
            seislearner
          </div>
        </div>
      </div>
      <div className="flex-1 min-h-0 flex flex-col">
        <Tabs
          activeKey={activeKey}
          onChange={handleTabChange}
          items={items}
          // className="h-full flex flex-col [&_.ant-tabs-content-holder]:flex-1 [&_.ant-tabs-content-holder]:min-h-0 [&_.ant-tabs-content]:h-full [&_.ant-tabs-tabpane]:h-full"
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
