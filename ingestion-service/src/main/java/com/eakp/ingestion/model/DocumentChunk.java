package com.eakp.ingestion.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * JSONB metadata: source filename, page number, section, etc.
     * Stored as JSONB in PostgreSQL for flexible querying.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }
}
