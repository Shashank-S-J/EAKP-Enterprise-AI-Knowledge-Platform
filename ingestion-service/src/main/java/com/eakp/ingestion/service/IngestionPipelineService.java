package com.eakp.ingestion.service;

import com.eakp.ingestion.exception.IngestionPipelineException;
import com.eakp.ingestion.messaging.IngestionCompletedEvent;
import com.eakp.ingestion.messaging.IngestionEventPublisher;
import com.eakp.ingestion.messaging.IngestionRequestedEvent;
import com.eakp.ingestion.model.Document;
import com.eakp.ingestion.model.DocumentChunk;
import com.eakp.ingestion.pipeline.DocumentParser;
import com.eakp.ingestion.pipeline.DocumentParser.ParsedDocument;
import com.eakp.ingestion.pipeline.EmbeddingService;
import com.eakp.ingestion.pipeline.TextChunker;
import com.eakp.ingestion.pipeline.TextChunker.TextChunk;
import com.eakp.ingestion.pipeline.VectorStoreWriter;
import com.eakp.ingestion.repository.DocumentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Core ingestion pipeline orchestrator.
 *
 * Pipeline steps:
 * 1. Mark document as PROCESSING
 * 2. Download raw bytes from MinIO
 * 3. Parse text (Apache Tika)
 * 4. Chunk text (recursive splitter)
 * 5. Embed chunks in batches (EmbeddingService)
 * 6. Write chunks + embeddings to pgvector (VectorStoreWriter)
 * 7. Mark document as READY
 * 8. Evict semantic cache for workspace
 * 9. Publish completion event
 *
 * Triggered by: RabbitMQ IngestionRequestedEvent (async)
 * OR direct call from DocumentService (sync, dev mode)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionPipelineService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final DocumentParser documentParser;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final VectorStoreWriter vectorStoreWriter;
    private final IngestionEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private Counter successCounter;
    private Counter failureCounter;
    private Timer pipelineTimer;

    @PostConstruct
    void initMetrics() {
        successCounter = Counter.builder("ingestion.documents.success")
                .description("Successfully ingested documents").register(meterRegistry);
        failureCounter = Counter.builder("ingestion.documents.failure")
                .description("Failed document ingestions").register(meterRegistry);
        pipelineTimer = Timer.builder("ingestion.pipeline.duration")
                .description("Full pipeline duration per document")
                .publishPercentiles(0.5, 0.95).register(meterRegistry);
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Process one ingestion request end-to-end.
     * Called by the RabbitMQ listener — runs on a virtual thread.
     */
    public void process(IngestionRequestedEvent event) {
        Instant start = Instant.now();
        log.info("▶ Ingestion started: document={} workspace={}",
                event.documentId(), event.workspaceId());

        // Step 1: Mark as PROCESSING
        updateStatus(event.documentId(), Document.DocumentStatus.PROCESSING, null, null);

        try {
            // Step 1b: Idempotency — clear any chunks from a previous (failed/retried)
            // run so we never accumulate duplicates if RabbitMQ redelivers the message.
            vectorStoreWriter.deleteByDocument(event.documentId());

            // Step 2: Download from MinIO
            log.debug("  [2/6] Downloading from storage: {}", event.storageKey());

            ParsedDocument parsed;
            try (InputStream stream = storageService.download(event.storageKey())) {
                // Step 3: Parse text
                log.debug("  [3/6] Parsing document: {}", event.filename());
                parsed = documentParser.parse(stream, event.filename());
            }

            // Step 4: Chunk text
            log.debug("  [4/6] Chunking text ({} chars)", parsed.text().length());
            List<TextChunk> chunks = textChunker.chunk(parsed.text(), event.filename());
            log.info("  Produced {} chunks", chunks.size());

            if (chunks.isEmpty()) {
                throw new IllegalStateException(
                        "No text chunks produced from document: " + event.filename());
            }

            // Step 5: Embed chunks
            log.debug("  [5/6] Embedding {} chunks", chunks.size());
            List<String> texts = chunks.stream()
                    .map(TextChunk::content)
                    .collect(Collectors.toList());
            List<float[]> embeddings = embeddingService.embedBatch(texts);

            // Step 6: Write to pgvector
            log.debug("  [6/6] Writing to vector store");
            List<DocumentChunk> saved = vectorStoreWriter.write(
                    chunks, embeddings,
                    event.documentId(), event.workspaceId(),
                    event.filename());

            // Step 7: Mark READY
            updateStatus(event.documentId(),
                    Document.DocumentStatus.READY, null, saved.size());

            // Step 8: Evict semantic cache so stale answers don't persist
            evictSemanticCache(event.workspaceId());

            // Step 9: Publish success event (best-effort — broker may be unavailable)
            try {
                eventPublisher.publishIngestionCompleted(
                        IngestionCompletedEvent.success(
                                event.documentId(), event.workspaceId(), saved.size()));
            } catch (Exception ex) {
                log.warn("Could not publish ingestion-completed event for document={} ({}). " +
                                "Pipeline succeeded; broker just isn't reachable.",
                        event.documentId(), ex.getMessage());
            }

            Duration elapsed = Duration.between(start, Instant.now());
            pipelineTimer.record(elapsed);
            successCounter.increment();

            log.info("✅ Ingestion complete: document={} chunks={} elapsed={}ms",
                    event.documentId(), saved.size(), elapsed.toMillis());

        } catch (Exception e) {
            log.error("❌ Ingestion failed: document={} error={}",
                    event.documentId(), e.getMessage(), e);

            updateStatus(event.documentId(),
                    Document.DocumentStatus.FAILED, e.getMessage(), 0);

            // Best-effort failure event
            try {
                eventPublisher.publishIngestionCompleted(
                        IngestionCompletedEvent.failure(
                                event.documentId(), event.workspaceId(),
                                e.getMessage()));
            } catch (Exception ignored) {
                // broker unreachable — status is already persisted in DB
            }

            failureCounter.increment();
            // Re-throw so caller (sync upload or RabbitMQ retry) knows
            throw new IngestionPipelineException("Ingestion pipeline failed", e);
        }
    }

    // ── Re-ingestion (delete old chunks, re-run pipeline) ────────────────────

    @Transactional
    public void reingest(UUID documentId, UUID workspaceId) {
        log.info("Re-ingesting document={}", documentId);

        Document doc = documentRepository.findByIdAndWorkspaceId(documentId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Document not found: " + documentId));

        // Delete existing chunks
        vectorStoreWriter.deleteByDocument(documentId);

        // Re-publish ingestion request
        eventPublisher.publishIngestionRequest(
                IngestionRequestedEvent.of(
                        doc.getId(), doc.getWorkspaceId(), doc.getUploadedBy(),
                        doc.getStorageKey(), doc.getFilename(), doc.getFileType()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Transactional
    void updateStatus(UUID documentId,
                      Document.DocumentStatus status,
                      String errorMsg,
                      Integer chunkCount) {
        documentRepository.updateStatus(documentId, status, errorMsg, chunkCount);
    }

    private void evictSemanticCache(UUID workspaceId) {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM semantic_cache WHERE workspace_id = ?::uuid",
                    workspaceId.toString());
            if (deleted > 0) {
                log.info("Evicted {} semantic cache entries for workspace={}",
                        deleted, workspaceId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict cache: {}", e.getMessage());
        }
    }
}