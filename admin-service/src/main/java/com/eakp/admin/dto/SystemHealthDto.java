package com.eakp.admin.dto;

public record SystemHealthDto(
    long pendingDocuments,
    long processingDocuments,
    long failedDocuments,
    long totalChunks,
    long activeUsers,
    long totalWorkspaces,
    long totalCacheEntries
) {}
