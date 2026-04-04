import React, { useState } from "react";
import { Sender } from "@ant-design/x";

interface AgentChatInputProps {
  onSend: (message: string) => void;
}

const AgentChatInput: React.FC<AgentChatInputProps> = ({ onSend }) => {
  const [message, setMessage] = useState("");

  return (
    <Sender
      onSubmit={() => {
        onSend(message.trim());
        setMessage("");
      }}
      placeholder="输入消息..."
      value={message}
      onChange={setMessage}
    />
  );
};

export default AgentChatInput;
