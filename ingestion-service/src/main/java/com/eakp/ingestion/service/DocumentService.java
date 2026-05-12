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

    private final DocumentRepository      documentRepository;
    private final StorageService          storageService;
    private final IngestionEventPublisher eventPublisher;
    private final VectorStoreWriter       vectorStoreWriter;
    private final IngestionPipelineService pipelineService;

    @Value("${app.ingestion.supported-types:pdf,docx,doc,txt,md,html,pptx,xlsx,csv,json,xml,rtf,htm}")
    private String supportedTypesConfig;

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Accept a file upload and run the full ingestion pipeline synchronously.
     *   1. Validate file type and size
     *   2. Save metadata to DB (status=PENDING)
     *   3. Upload raw bytes to object storage
     *   4. Run the pipeline IN-LINE: parse → chunk → embed → write vectors
     *      → mark READY (or FAILED on error)
     *   5. Best-effort fire RabbitMQ "completed" event for downstream consumers
     *   6. Return the document DTO (already READY when this returns)
     *
     * Synchronous because Render free-tier single-instance deployment can't
     * reliably consume RabbitMQ events (sleeps, no durable consumer process).
     * Doing the work in-line guarantees the doc is ingested by the time the
     * upload response returns.
     */
    public DocumentDto upload(MultipartFile file,
                              UUID workspaceId,
                              UUID uploadedBy) throws Exception {
        // Validate
        validateFile(file);

        String filename = sanitizeFilename(file.getOriginalFilename());
        String fileType = detectExtension(filename);
        UUID   docId    = UUID.randomUUID();

        // Save metadata (PENDING) and upload bytes in a short DB transaction
        Document doc = saveInitialMetadata(docId, workspaceId, uploadedBy,
                filename, fileType, file.getSize());

        String storageKey = storageService.upload(file, workspaceId, docId);
        updateStorageKey(doc, storageKey);

        log.info("Document stored: id={} name='{}' size={}B ws={} — starting pipeline",
                docId, filename, file.getSize(), workspaceId);

        // Run the pipeline synchronously — throws on failure (mapped to 500 by handler)
        IngestionRequestedEvent event = IngestionRequestedEvent.of(
                docId, workspaceId, uploadedBy, storageKey, filename, fileType);
        pipelineService.process(event);

        // Re-fetch so caller sees the final READY status + chunk_count
        Document refreshed = documentRepository.findById(docId).orElse(doc);
        return toDto(refreshed);
    }

    @Transactional
    protected Document saveInitialMetadata(UUID docId, UUID workspaceId, UUID uploadedBy,
                                           String filename, String fileType, long size) {
        return documentRepository.save(Document.builder()
                .id(docId)
                .workspaceId(workspaceId)
                .uploadedBy(uploadedBy)
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
        if (file.getSize() > 50 * 1024 * 1024) {  // 50MB (matches frontend)
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
        if (filename == null || !filename.contains(".")) return "unknown";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private String sanitizeFilename(String filename) {
        if (filename == null) return "document";
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
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }
}