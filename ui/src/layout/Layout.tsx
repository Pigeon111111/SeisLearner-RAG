import React from "react";

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  return (
    <div className="min-h-[100dvh] flex flex-col lg:flex-row">
      {children}
    </div>
  );
};

export default Layout;
