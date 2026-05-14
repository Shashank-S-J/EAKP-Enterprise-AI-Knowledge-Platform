package com.eakp.ingestion.service;

import com.eakp.ingestion.messaging.IngestionRequestedEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs the ingestion pipeline on a virtual-thread executor so the HTTP
 * upload request returns immediately (202 ACCEPTED) instead of blocking
 * for 30–90 seconds while Tika parses, Mistral embeds, and pgvector writes.
 *
 * <p>A small semaphore caps in-flight pipelines so a burst of uploads
 * can't OOM the 512 MB Render free-tier dyno.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncPipelineRunner {

    /** Max concurrent ingestions — keep low for 512 MB heap. */
    private static final int MAX_CONCURRENT = 2;

    private final IngestionPipelineService pipelineService;

    private final ExecutorService executor =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                    .name("ingest-async-", 0)
                    .factory());

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    /** Submit an event for background processing. Returns immediately. */
    public void submit(IngestionRequestedEvent event) {
        executor.execute(() -> {
            try {
                slots.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting to start pipeline for document={}",
                        event.documentId());
                return;
            }
            try {
                pipelineService.process(event);
            } catch (Exception e) {
                // pipelineService.process already persists FAILED status; just log.
                log.error("Async pipeline failed for document={}: {}",
                        event.documentId(), e.getMessage());
            } finally {
                slots.release();
            }
        });
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down async pipeline executor (waiting up to 30s)…");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}