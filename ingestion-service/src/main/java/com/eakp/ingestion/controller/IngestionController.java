package com.eakp.ingestion.controller;

import com.eakp.ingestion.dto.DocumentDto;
import com.eakp.ingestion.service.DocumentService;
import com.eakp.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
@Slf4j
public class IngestionController {

    private final DocumentService         documentService;
    private final IngestionPipelineService pipelineService;

    /**
     * POST /api/v1/documents/upload
     * Upload a document. Returns immediately (PENDING status).
     * Poll GET /{id} to check when status becomes READY.
     *
     * Request: multipart/form-data, field name = "file"
     */
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentDto upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) UUID conversationId,
            Authentication auth) throws Exception {

        UUID workspaceId = workspaceId(auth);
        UUID userId      = userId(auth);

        // Validate file is present before processing
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        log.info("Upload request: file='{}' size={}B ws={} conv={}",
                file.getOriginalFilename(), file.getSize(), workspaceId, conversationId);

        return documentService.upload(file, workspaceId, userId, conversationId);
    }

    /**
     * GET /api/v1/documents
     * List documents in the workspace. Supports pagination.
     * Query params: page (0-based, default 0), size (default 50, max 200)
     */
    @GetMapping
    public List<DocumentDto> list(
            Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 200); // guard against excessive page sizes
        return documentService.listDocuments(workspaceId(auth), page, size);
    }

    /**
     * GET /api/v1/documents/{id}
     * Get a single document's status and metadata.
     * Poll this after upload to check when ingestion is READY.
     */
    @GetMapping("/{id}")
    public DocumentDto get(@PathVariable UUID id, Authentication auth) {
        return documentService.getDocument(id, workspaceId(auth));
    }

    /**
     * DELETE /api/v1/documents/{id}
     * Delete a document: removes from storage, vector DB, and metadata DB.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) throws Exception {
        documentService.deleteDocument(id, workspaceId(auth));
    }

    /**
     * GET /api/v1/documents/{id}/download-url
     * Get a presigned MinIO URL for direct document download (1hr expiry).
     */
    @GetMapping("/{id}/download-url")
    public ResponseEntity<Map<String, String>> downloadUrl(
            @PathVariable UUID id, Authentication auth) throws Exception {
        String url = documentService.getDownloadUrl(id, workspaceId(auth));
        return ResponseEntity.ok(Map.of("url", url));
    }

    /**
     * POST /api/v1/documents/{id}/reingest
     * Force re-ingestion (delete old chunks, re-process from storage).
     * Useful if chunking/embedding config changed.
     */
    @PostMapping("/{id}/reingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<Map<String, String>> reingest(
            @PathVariable UUID id, Authentication auth) {
        pipelineService.reingest(id, workspaceId(auth));
        return ResponseEntity.accepted()
                .body(Map.of("message", "Re-ingestion started", "documentId", id.toString()));
    }


    // ── Auth helpers ──────────────────────────────────────────────────────────

    private UUID workspaceId(Authentication auth) {
        Object creds = auth.getCredentials();
        return creds instanceof UUID u ? u : UUID.fromString(creds.toString());
    }

    private UUID userId(Authentication auth) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object userId = attrs.getRequest().getAttribute("userId");
            if (userId instanceof UUID u) return u;
        }
        return UUID.nameUUIDFromBytes(
                auth.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}