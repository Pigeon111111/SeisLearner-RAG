package seislearner.agent;

public enum AgentState {
    IDLE,  // 空闲
    PLANNING,  // 计划?
    THINKING,  // 思考中
    EXECUTING, // 执行?
    FINISHED,  // 正常结束
    ERROR  // 错误结束
}
