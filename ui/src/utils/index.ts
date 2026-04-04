export const getAgentEmoji = (agentId: string): string => {
  // 使用 agent id 的哈希值来选择 emoji，确保同一个 agent 总是显示相同的 emoji
  const EMOJI_LIST = [
    "🤖",
    "🎯",
    "🚀",
    "💡",
    "🔮",
    "⚡",
    "🌟",
    "🎨",
    "🔧",
    "📚",
  ];
  let hash = 0;
  for (let i = 0; i < agentId.length; i++) {
    hash = (hash << 5) - hash + agentId.charCodeAt(i);
    hash = hash & hash; // Convert to 32bit integer
  }
  const index = Math.abs(hash) % EMOJI_LIST.length;
  return EMOJI_LIST[index];
};

export const getKnowledgeBaseEmoji = (knowledgeBaseId: string): string => {
  // 知识库相关的 emoji 列表
  const KNOWLEDGE_BASE_EMOJI_LIST = [
    "📚",
    "📖",
    "📝",
    "📋",
    "📑",
    "📄",
    "📃",
    "📊",
    "📈",
    "📉",
  ];
  // 使用知识库 id 的哈希值来选择 emoji，确保同一个知识库总是显示相同的 emoji
  let hash = 0;
  for (let i = 0; i < knowledgeBaseId.length; i++) {
    hash = (hash << 5) - hash + knowledgeBaseId.charCodeAt(i);
    hash = hash & hash; // Convert to 32bit integer
  }
  const index = Math.abs(hash) % KNOWLEDGE_BASE_EMOJI_LIST.length;
  return KNOWLEDGE_BASE_EMOJI_LIST[index];
};

export const formatDateTime = (dateString?: string): string => {
  if (!dateString) return "";
  const date = new Date(dateString);
  const now = new Date();
  const diff = now.getTime() - date.getTime();
  const days = Math.floor(diff / (1000 * 60 * 60 * 24));
  
  if (days === 0) {
    const hours = Math.floor(diff / (1000 * 60 * 60));
    if (hours === 0) {
      const minutes = Math.floor(diff / (1000 * 60));
      return minutes <= 0 ? "刚刚" : `${minutes}分钟前`;
    }
    return `${hours}小时前`;
  } else if (days === 1) {
    return "昨天";
  } else if (days < 7) {
    return `${days}天前`;
  } else {
    return date.toLocaleDateString("zh-CN", {
      month: "short",
      day: "numeric",
    });
  }
};
