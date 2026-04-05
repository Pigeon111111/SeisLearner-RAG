import React from "react";

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  return (
    <div className="h-screen flex flex-col lg:flex-row overflow-hidden">
      {children}
    </div>
  );
};

export default Layout;
