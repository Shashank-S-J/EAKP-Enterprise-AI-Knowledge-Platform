package com.eakp.ingestion.service;

import com.eakp.ingestion.messaging.IngestionRequestedEvent;
import com.eakp.ingestion.model.Document;
import com.eakp.ingestion.model.Document.DocumentStatus;
import com.eakp.ingestion.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Recovers documents stuck in PENDING (never started) or stale PROCESSING
 * (pod crashed mid-pipeline — e.g. Render free-tier sleep, OOM, deploy).
 *
 * <p>Runs every 2 minutes. Idempotent: the pipeline deletes prior chunks
 * for the document before re-writing, so re-runs are safe.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionRecoveryScheduler {

    /** Skip docs newer than this — they're likely already being processed. */
    private static final Duration PENDING_GRACE   = Duration.ofMinutes(1);
    /** PROCESSING older than this is presumed crashed. */
    private static final Duration PROCESSING_STALE = Duration.ofMinutes(10);

    private final DocumentRepository documentRepository;
    private final AsyncPipelineRunner asyncRunner;

    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT45S")
    public void recover() {
        Instant now = Instant.now();
        Instant pendingCutoff   = now.minus(PENDING_GRACE);
        Instant processingCutoff = now.minus(PROCESSING_STALE);

        List<Document> stuck = documentRepository.findByStatus(DocumentStatus.PENDING).stream()
                .filter(d -> d.getStorageKey() != null
                        && !d.getStorageKey().equals("pending")
                        && d.getUpdatedAt() != null
                        && d.getUpdatedAt().isBefore(pendingCutoff))
                .toList();

        List<Document> staleProcessing = documentRepository.findByStatus(DocumentStatus.PROCESSING).stream()
                .filter(d -> d.getUpdatedAt() != null
                        && d.getUpdatedAt().isBefore(processingCutoff))
                .toList();

        int total = stuck.size() + staleProcessing.size();
        if (total == 0) return;

        log.info("Recovery: resubmitting {} PENDING + {} stale PROCESSING document(s)",
                stuck.size(), staleProcessing.size());

        for (Document d : stuck)          submit(d);
        for (Document d : staleProcessing) submit(d);
    }

    private void submit(Document d) {
        try {
            asyncRunner.submit(IngestionRequestedEvent.of(
                    d.getId(), d.getWorkspaceId(), d.getUploadedBy(),
                    d.getStorageKey(), d.getFilename(), d.getFileType()));
        } catch (Exception e) {
            log.warn("Recovery resubmit failed for document={}: {}", d.getId(), e.getMessage());
        }
    }
}