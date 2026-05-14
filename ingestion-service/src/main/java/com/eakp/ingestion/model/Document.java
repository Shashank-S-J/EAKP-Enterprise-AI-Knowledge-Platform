package com.eakp.ingestion.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Document {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Optional: when set, this document is scoped to a single conversation
     *  (ChatGPT-style attachment). NULL means workspace-wide. */
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(nullable = false)
    private String filename;

    @Column(name = "file_type", nullable = false, length = 50)
    private String fileType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "storage_key", nullable = false, length = 1000)
    private String storageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DocumentStatus status;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = updatedAt = Instant.now();
        if (status == null) status = DocumentStatus.PENDING;
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    public enum DocumentStatus {
        PENDING, PROCESSING, READY, FAILED
    }
}