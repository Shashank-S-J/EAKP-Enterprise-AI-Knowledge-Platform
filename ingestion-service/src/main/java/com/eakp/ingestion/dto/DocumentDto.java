package com.eakp.ingestion.dto;

import java.time.Instant;

public record DocumentDto(
        String  id,
        String  filename,
        String  fileType,
        long    fileSize,
        String  status,        // PENDING | PROCESSING | READY | FAILED
        Integer chunkCount,
        String  errorMessage,
        String  conversationId, // null = workspace-wide, else attached to a chat
        Instant createdAt,
        Instant updatedAt
) {}