import React from "react";

interface SidebarProps {
  children: React.ReactNode;
}

const Sidebar: React.FC<SidebarProps> = ({ children }) => {
  return (
    <div className="h-full bg-slate-50 shrink-0 w-72 lg:w-80 overflow-hidden">
      {children}
    </div>
  );
};

export default Sidebar;
