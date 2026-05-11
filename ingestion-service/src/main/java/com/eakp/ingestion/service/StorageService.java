package com.eakp.ingestion.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Handles all interactions with S3-compatible object storage (Supabase, MinIO, AWS S3).
 *
 * Bucket structure:
 *   eakp-documents/{workspaceId}/{documentId}/{filename}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private final S3Client s3Client;

    @Value("${app.storage.bucket}")
    private String bucket;

    @Value("${app.storage.endpoint}")
    private String endpoint;

    @Value("${app.storage.access-key}")
    private String accessKey;

    @Value("${app.storage.secret-key}")
    private String secretKey;

    @Value("${app.storage.region:us-east-1}")
    private String region;

    // ── Init ──────────────────────────────────────────────────────────────────

    @PostConstruct
    void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Created S3 bucket: {}", bucket);
            } catch (Exception ex) {
                log.error("Failed to create S3 bucket: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.error("Failed to check S3 bucket: {}", e.getMessage());
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a multipart file to S3.
     * Returns the object key for later retrieval.
     */
    public String upload(MultipartFile file, UUID workspaceId,
                         UUID documentId) throws Exception {
        String key = buildKey(workspaceId, documentId, file.getOriginalFilename());

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(file.getContentType() != null
                                ? file.getContentType() : "application/octet-stream")
                        .contentLength(file.getSize())
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        log.info("Uploaded {} → {}", file.getOriginalFilename(), key);
        return key;
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Stream the raw bytes of a stored document.
     * Used by the ingestion pipeline to parse content.
     */
    public InputStream download(String storageKey) throws Exception {
        return s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(storageKey)
                        .build());
    }

    // ── Presigned URL ─────────────────────────────────────────────────────────

    /**
     * Generate a time-limited presigned URL for direct client download.
     * Expires in 1 hour by default.
     */
    public String presignedDownloadUrl(String storageKey) throws Exception {
        try (S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build()) {

            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(storageKey)
                            .build())
                    .build()).url().toString();
        }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    public void delete(String storageKey) throws Exception {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
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