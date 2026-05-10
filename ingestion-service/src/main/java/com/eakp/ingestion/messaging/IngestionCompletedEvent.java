package com.eakp.ingestion.messaging;

import java.time.Instant;
import java.util.UUID;

/** Published when ingestion completes (success or failure). */
public record IngestionCompletedEvent(
    UUID    documentId,
    UUID    workspaceId,
    String  status,        // "READY" | "FAILED"
    int     chunkCount,
    String  errorMessage,
    Instant completedAt
) {
    public static IngestionCompletedEvent success(UUID documentId,
                                                   UUID workspaceId,
                                                   int chunkCount) {
        return new IngestionCompletedEvent(documentId, workspaceId,
                "READY", chunkCount, null, Instant.now());
    }

    public static IngestionCompletedEvent failure(UUID documentId,
                                                   UUID workspaceId,
                                                   String error) {
        return new IngestionCompletedEvent(documentId, workspaceId,
                "FAILED", 0, error, Instant.now());
    }
}
