package seislearner.model.entity;

import java.time.LocalDateTime;
import java.util.Arrays;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName chunk_bge_m3
 */
@Data
@Builder
public class ChunkBgeM3 {
    private String id;

    private String kbId;

    private String docId;

    private String content;

    private String metadata;

    private float[] embedding;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
    
    // 来源追溯字段
    private Integer startOffset; // 在原始文档中的起始偏移
    private Integer endOffset;   // 结束偏移
    private String parentChunkId; // 父分块ID（用于合并）
    private String imageReferences; // 关联的图像引用（JSON数组）
    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        ChunkBgeM3 other = (ChunkBgeM3) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getKbId() == null ? other.getKbId() == null : this.getKbId().equals(other.getKbId()))
            && (this.getDocId() == null ? other.getDocId() == null : this.getDocId().equals(other.getDocId()))
            && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
            && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
            && (this.getEmbedding() == null ? other.getEmbedding() == null : Arrays.equals(this.getEmbedding(), other.getEmbedding()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
            && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()))
            && (this.getStartOffset() == null ? other.getStartOffset() == null : this.getStartOffset().equals(other.getStartOffset()))
            && (this.getEndOffset() == null ? other.getEndOffset() == null : this.getEndOffset().equals(other.getEndOffset()))
            && (this.getParentChunkId() == null ? other.getParentChunkId() == null : this.getParentChunkId().equals(other.getParentChunkId()))
            && (this.getImageReferences() == null ? other.getImageReferences() == null : this.getImageReferences().equals(other.getImageReferences()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getKbId() == null) ? 0 : getKbId().hashCode());
        result = prime * result + ((getDocId() == null) ? 0 : getDocId().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getEmbedding() == null) ? 0 : Arrays.hashCode(getEmbedding()));
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        result = prime * result + ((getStartOffset() == null) ? 0 : getStartOffset().hashCode());
        result = prime * result + ((getEndOffset() == null) ? 0 : getEndOffset().hashCode());
        result = prime * result + ((getParentChunkId() == null) ? 0 : getParentChunkId().hashCode());
        result = prime * result + ((getImageReferences() == null) ? 0 : getImageReferences().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", id=" + id +
                ", kbId=" + kbId +
                ", docId=" + docId +
                ", content=" + content +
                ", metadata=" + metadata +
                ", embedding=" + Arrays.toString(embedding) +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", startOffset=" + startOffset +
                ", endOffset=" + endOffset +
                ", parentChunkId=" + parentChunkId +
                ", imageReferences=" + imageReferences +
                "]";
    }
}