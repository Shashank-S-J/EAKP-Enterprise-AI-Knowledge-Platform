package com.eakp.ingestion.service;

import com.eakp.ingestion.dto.DocumentDto;
import com.eakp.ingestion.messaging.IngestionEventPublisher;
import com.eakp.ingestion.messaging.IngestionRequestedEvent;
import com.eakp.ingestion.model.Document;
import com.eakp.ingestion.model.Document.DocumentStatus;
import com.eakp.ingestion.pipeline.VectorStoreWriter;
import com.eakp.ingestion.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final IngestionEventPublisher eventPublisher;
    private final VectorStoreWriter vectorStoreWriter;
    private final IngestionPipelineService pipelineService;
    private final AsyncPipelineRunner asyncPipelineRunner;

    @Value("${app.ingestion.supported-types:pdf,docx,doc,txt,md,html,pptx,xlsx,csv,json,xml,rtf,htm}")
    private String supportedTypesConfig;

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Accept a file upload and dispatch the ingestion pipeline asynchronously.
     * 1. Validate file type and size
     * 2. Save metadata to DB (status=PENDING)
     * 3. Upload raw bytes to object storage
     * 4. Hand the pipeline (parse → chunk → embed → write vectors) to a
     * background virtual-thread executor and return the PENDING DTO
     * immediately so the HTTP request finishes in seconds, not minutes.
     *
     * Why async: synchronous ingestion on Render's free tier held the HTTP/2
     * connection for 30–90 s while embedding ran, which the edge proxy
     * regularly reset → browsers saw ERR_HTTP2_PROTOCOL_ERROR and
     * subsequent 403s. With this design the upload returns in ~2–4 s
     * (S3 PUT + DB insert) and the client polls GET /{id} for readiness.
     *
     * Resilience: {@link IngestionRecoveryScheduler} sweeps stuck PENDING
     * and stale PROCESSING rows every 2 minutes, so a pod crash mid-pipeline
     * is self-healed on the next pass.
     */
    public DocumentDto upload(MultipartFile file,
                              UUID workspaceId,
                              UUID uploadedBy,
                              UUID conversationId) throws Exception {
        // Validate
        validateFile(file);

        String filename = sanitizeFilename(file.getOriginalFilename());
        String fileType = detectExtension(filename);
        UUID docId = UUID.randomUUID();

        // Save metadata (PENDING) and upload bytes in a short DB transaction
        Document doc = saveInitialMetadata(docId, workspaceId, uploadedBy,
                filename, fileType, file.getSize(), conversationId);

        String storageKey = storageService.upload(file, workspaceId, docId);
        updateStorageKey(doc, storageKey);

        log.info("Document stored: id={} name='{}' size={}B ws={} conv={} — dispatching pipeline (async)",
                docId, filename, file.getSize(), workspaceId, conversationId);

        // Fire-and-forget: pipeline runs on a virtual thread, request returns now.
        asyncPipelineRunner.submit(IngestionRequestedEvent.of(
                docId, workspaceId, uploadedBy, storageKey, filename, fileType));

        // Return PENDING — client polls GET /api/v1/documents/{id} for READY.
        return toDto(doc);
    }

    @Transactional
    protected Document saveInitialMetadata(UUID docId, UUID workspaceId, UUID uploadedBy,
                                           String filename, String fileType, long size, UUID conversationId) {
        return documentRepository.save(Document.builder()
                .id(docId)
                .workspaceId(workspaceId)
                .uploadedBy(uploadedBy)
                .conversationId(conversationId)
                .filename(filename)
                .fileType(fileType)
                .fileSize(size)
                .storageKey("pending")
                .status(DocumentStatus.PENDING)
                .build());
    }

    @Transactional
    protected void updateStorageKey(Document doc, String storageKey) {
        doc.setStorageKey(storageKey);
        documentRepository.save(doc);
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(UUID workspaceId) {
        return listDocuments(workspaceId, 0, 50);
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> listDocuments(UUID workspaceId, int page, int size) {
        return documentRepository
                .findByWorkspaceId(workspaceId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DocumentDto getDocument(UUID documentId, UUID workspaceId) {
        Document doc = documentRepository
                .findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Document not found: " + documentId));
        return toDto(doc);
    }

    /** Documents attached to a specific conversation — ordered newest first. */
    @Transactional(readOnly = true)
    public List<DocumentDto> listByConversation(UUID workspaceId, UUID conversationId) {
        return documentRepository
                .findByWorkspaceIdAndConversationIdOrderByCreatedAtDesc(
                        workspaceId, conversationId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteDocument(UUID documentId, UUID workspaceId) throws Exception {
        Document doc = documentRepository
                .findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Document not found: " + documentId));

        // Delete vectors first (transactional with DB)
        vectorStoreWriter.deleteByDocument(documentId);

        // Delete from object store (non-transactional — best effort)
        try {
            storageService.delete(doc.getStorageKey());
        } catch (Exception e) {
            log.warn("Could not delete from storage: {}", e.getMessage());
        }

        documentRepository.delete(doc);
        log.info("Deleted document: id={} name='{}'", documentId, doc.getFilename());
    }

    // ── Download URL ──────────────────────────────────────────────────────────

    public String getDownloadUrl(UUID documentId, UUID workspaceId) throws Exception {
        Document doc = documentRepository
                .findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Document not found: " + documentId));
        return storageService.presignedDownloadUrl(doc.getStorageKey());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > 50 * 1024 * 1024) { // 50MB (matches frontend)
            throw new IllegalArgumentException("File exceeds 50MB limit");
        }
        String ext = detectExtension(file.getOriginalFilename());
        Set<String> supported = Set.of(supportedTypesConfig.split(","));
        if (!supported.contains(ext)) {
            throw new IllegalArgumentException(
                    "Unsupported file type: ." + ext +
                            ". Supported: " + supportedTypesConfig);
        }
        // Also validate MIME content type when available
        String contentType = file.getContentType();
        if (contentType != null) {
            Set<String> allowedMimes = Set.of(
                    "application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/plain", "text/html", "text/csv", "text/markdown",
                    "application/json", "application/xml", "text/xml", "application/rtf",
                    "application/octet-stream" // fallback for unknown types
            );
            if (!allowedMimes.contains(contentType)) {
                log.warn("Suspicious MIME type '{}' for file '{}'", contentType, file.getOriginalFilename());
            }
        }
    }

    private String detectExtension(String filename) {
        if (filename == null || !filename.contains("."))
            return "unknown";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String sanitizeFilename(String filename) {
        if (filename == null)
            return "document";
        return filename.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private DocumentDto toDto(Document d) {
        return new DocumentDto(
                d.getId().toString(),
                d.getFilename(),
                d.getFileType(),
                d.getFileSize(),
                d.getStatus().name(),
                d.getChunkCount(),
                d.getErrorMsg(),
                d.getConversationId() != null ? d.getConversationId().toString() : null,
                d.getCreatedAt(),
                d.getUpdatedAt());
    }
}