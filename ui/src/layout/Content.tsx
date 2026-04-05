import React from "react";

interface ContentProps {
  children: React.ReactNode;
}

const Content: React.FC<ContentProps> = ({ children }) => {
  return (
    <div className="flex-1 min-h-0 flex flex-col overflow-hidden">
      {children}
    </div>
  );
};

export default Content;
