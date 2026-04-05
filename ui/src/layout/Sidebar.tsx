import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <aside className="h-full bg-slate-50 shrink-0 w-full lg:w-72 xl:w-80 overflow-hidden border-b lg:border-b-0 lg:border-r border-gray-200">
      {children}
    </aside>
  );
};

export default Sidebar;
