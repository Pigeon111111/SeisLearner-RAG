import { useState } from "react";
import { Routes, Route } from "react-router-dom";
import { MenuOutlined, CloseOutlined } from "@ant-design/icons";
import Layout from "../layout/Layout.tsx";
import Sidebar from "../layout/Sidebar.tsx";
import SideMenu from "./SideMenu.tsx";
import Content from "../layout/Content.tsx";
import AgentChatView from "./views/AgentChatView.tsx";
import KnowledgeBaseView from "./views/KnowledgeBaseView.tsx";

export default function SeisLearnerLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <Layout>
      {/* 移动端遮罩层 */}
      {sidebarOpen && (
        <div 
          className="fixed inset-0 bg-black/50 z-40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}
      
      {/* 侧边栏 - 移动端固定定位，桌面端正常流 */}
      <div className={`
        fixed lg:relative inset-y-0 left-0 z-50 lg:z-auto
        transform transition-transform duration-300 ease-in-out
        ${sidebarOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}
      `}>
        <Sidebar>
          {/* 移动端关闭按钮 */}
          <button
            className="absolute top-3 right-3 lg:hidden p-1.5 rounded-md hover:bg-gray-200 transition-colors"
            onClick={() => setSidebarOpen(false)}
          >
            <CloseOutlined className="text-gray-500" />
          </button>
          <SideMenu />
        </Sidebar>
      </div>
      
      <Content>
        {/* 移动端菜单按钮 */}
        <div className="lg:hidden h-12 flex items-center px-3 border-b border-gray-200 bg-white">
          <button
            className="p-2 rounded-md hover:bg-gray-100 transition-colors"
            onClick={() => setSidebarOpen(true)}
          >
            <MenuOutlined className="text-lg text-gray-600" />
          </button>
          <span className="ml-3 font-medium text-gray-800">SeisLearner</span>
        </div>
        
        <Routes>
          <Route path="/" element={<AgentChatView />} />
          <Route path="/agent" element={<AgentChatView />} />
          <Route path="/chat" element={<AgentChatView />} />
          <Route path="/chat/:chatSessionId" element={<AgentChatView />} />
          <Route path="/knowledge-base" element={<KnowledgeBaseView />} />
          <Route
            path="/knowledge-base/:knowledgeBaseId"
            element={<KnowledgeBaseView />}
          />
        </Routes>
      </Content>
    </Layout>
  );
}
