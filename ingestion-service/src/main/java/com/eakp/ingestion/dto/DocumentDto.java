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
    Instant createdAt,
    Instant updatedAt
) {}
