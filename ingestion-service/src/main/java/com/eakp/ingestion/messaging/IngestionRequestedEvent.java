package com.eakp.ingestion.messaging;

import java.time.Instant;
import java.util.UUID;

/** Published to RabbitMQ to trigger async document ingestion. */
public record IngestionRequestedEvent(
    UUID    documentId,
    UUID    workspaceId,
    UUID    uploadedBy,
    String  storageKey,
    String  filename,
    String  fileType,
    Instant requestedAt
) {
    public static IngestionRequestedEvent of(UUID documentId, UUID workspaceId,
                                              UUID uploadedBy, String storageKey,
                                              String filename, String fileType) {
        return new IngestionRequestedEvent(documentId, workspaceId, uploadedBy,
                storageKey, filename, fileType, Instant.now());
    }
}
