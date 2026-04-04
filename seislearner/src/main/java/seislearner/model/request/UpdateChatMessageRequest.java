package seislearner.model.request;

import seislearner.model.dto.ChatMessageDTO;
import lombok.Data;

@Data
public class UpdateChatMessageRequest {
    private String content;
    private ChatMessageDTO.MetaData metadata;
}

