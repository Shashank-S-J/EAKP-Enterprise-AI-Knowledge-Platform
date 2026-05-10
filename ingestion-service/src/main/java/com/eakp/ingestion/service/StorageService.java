package com.eakp.ingestion.service;

import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Handles all interactions with MinIO (S3-compatible) object storage.
 *
 * Bucket structure:
 *   eakp-documents/{workspaceId}/{documentId}/{filename}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private final MinioClient minioClient;

    @Value("${app.storage.bucket}")
    private String bucket;

    // ── Init ──────────────────────────────────────────────────────────────────

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to initialise MinIO bucket: {}", e.getMessage());
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a multipart file to MinIO.
     * Returns the object key for later retrieval.
     */
    public String upload(MultipartFile file, UUID workspaceId,
                         UUID documentId) throws Exception {
        String key = buildKey(workspaceId, documentId, file.getOriginalFilename());

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(key)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType() != null
                        ? file.getContentType() : "application/octet-stream")
                .build());

        log.info("Uploaded {} → {}", file.getOriginalFilename(), key);
        return key;
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Stream the raw bytes of a stored document.
     * Used by the ingestion pipeline to parse content.
     */
    public InputStream download(String storageKey) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(storageKey)
                .build());
    }

    // ── Presigned URL ─────────────────────────────────────────────────────────

    /**
     * Generate a time-limited presigned URL for direct client download.
     * Expires in 1 hour by default.
     */
    public String presignedDownloadUrl(String storageKey) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .bucket(bucket)
                .object(storageKey)
                .method(Method.GET)
                .expiry(1, TimeUnit.HOURS)
                .build());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void delete(String storageKey) throws Exception {
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(storageKey)
                .build());
        log.info("Deleted from storage: {}", storageKey);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildKey(UUID workspaceId, UUID documentId, String filename) {
        // Sanitise filename to avoid path traversal
        String safe = filename != null
                ? filename.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "document";
        return "%s/%s/%s".formatted(workspaceId, documentId, safe);
    }
}
