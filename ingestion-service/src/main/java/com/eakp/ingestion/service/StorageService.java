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

    /**
     * Verify the configured bucket exists and is reachable. Fails fast at startup
     * if not — prevents the silent "DB row created but no file in storage" symptom
     * users see when env vars (endpoint / region / bucket name) are wrong or the
     * bucket was never created via the provider's dashboard.
     *
     * NOTE: We do NOT attempt to create the bucket. Supabase Storage rejects
     * S3 CreateBucket calls — buckets must be created via the Supabase UI.
     */
    @PostConstruct
    void ensureBucketExists() {
        log.info("Storage config: endpoint={} region={} bucket={}", endpoint, region, bucket);
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Storage bucket '{}' is reachable", bucket);
        } catch (NoSuchBucketException e) {
            throw new IllegalStateException(
                    "Storage bucket '" + bucket + "' does not exist at " + endpoint +
                            ". Create it in your provider's dashboard (Supabase: Storage → New bucket) " +
                            "and ensure MINIO_BUCKET matches exactly (case-sensitive).", e);
        } catch (S3Exception e) {
            throw new IllegalStateException(
                    "Cannot access storage bucket '" + bucket + "' at " + endpoint +
                            " (HTTP " + e.statusCode() + "): " + e.awsErrorDetails().errorMessage() +
                            ". Check MINIO_ENDPOINT / MINIO_REGION / MINIO_ACCESS_KEY / MINIO_SECRET_KEY.", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to verify storage bucket '" + bucket + "' at " + endpoint +
                            ": " + e.getMessage(), e);
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a multipart file to S3-compatible storage.
     * Verifies the object exists after PUT so silent failures (wrong region,
     * misrouted requests) become loud instead of leaving orphan DB rows.
     * Returns the object key for later retrieval.
     */
    public String upload(MultipartFile file, UUID workspaceId,
                         UUID documentId) throws Exception {
        String key = buildKey(workspaceId, documentId, file.getOriginalFilename());

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType() != null
                                    ? file.getContentType() : "application/octet-stream")
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (S3Exception e) {
            log.error("S3 putObject failed for bucket={} key={} (HTTP {}): {}",
                    bucket, key, e.statusCode(), e.awsErrorDetails().errorMessage());
            throw e;
        }

        // Verify the object actually landed in the expected bucket.
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            log.info("Uploaded {} -> s3://{}/{} ({} bytes)",
                    file.getOriginalFilename(), bucket, key, head.contentLength());
        } catch (Exception e) {
            log.error("PUT to s3://{}/{} reported success but verification failed: {}",
                    bucket, key, e.getMessage());
            throw new IllegalStateException(
                    "Upload to bucket '" + bucket + "' at " + endpoint + " appeared to succeed " +
                            "but the object cannot be read back. Likely cause: MINIO_REGION does not match " +
                            "the Supabase project region, or MINIO_BUCKET name is wrong.", e);
        }
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