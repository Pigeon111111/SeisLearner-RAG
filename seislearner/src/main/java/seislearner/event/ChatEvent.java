package seislearner.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatEvent {
    private String agentId;
    private String sessionId;
    private String userInput;
}
