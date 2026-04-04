package seislearner.model.response;

import seislearner.model.vo.DocumentVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetDocumentsResponse {
    private DocumentVO[] documents;
}

