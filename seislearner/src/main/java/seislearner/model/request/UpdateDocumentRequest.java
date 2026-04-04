package seislearner.model.request;

import lombok.Data;

@Data
public class UpdateDocumentRequest {
    private String filename;
    private String filetype;
    private Long size;
}

