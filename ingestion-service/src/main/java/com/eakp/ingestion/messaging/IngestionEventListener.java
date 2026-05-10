package com.eakp.ingestion.messaging;

import com.eakp.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ consumer for document ingestion events.
 *
 * Uses Spring AMQP @RabbitListener with JSON deserialization (configured
 * in RabbitConfig). Runs on virtual threads (spring.threads.virtual=true).
 *
 * Retry: configured in application.yml (3 attempts, exponential backoff).
 * DLQ:   failed messages after retries go to eakp.ingest.dlq.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionEventListener {

    private final IngestionPipelineService pipelineService;
    private final RabbitTemplate           rabbitTemplate;

    @Value("${app.rabbitmq.queue.ingest}")
    private String ingestQueue;

    // ── Main consumer ─────────────────────────────────────────────────────────

    @RabbitListener(queues = "${app.rabbitmq.queue.ingest}",
                    containerFactory = "rabbitListenerContainerFactory")
    public void onIngestionRequested(IngestionRequestedEvent event) {
        log.info("📨 Received ingestion request: document={} file='{}'",
                event.documentId(), event.filename());
        // Pipeline throws on failure → message goes to DLQ after retries
        pipelineService.process(event);
    }

    // ── DLQ monitor (for observability) ──────────────────────────────────────

    @RabbitListener(queues = "${app.rabbitmq.queue.ingest-dlq}",
                    containerFactory = "rabbitListenerContainerFactory")
    public void onDeadLetter(IngestionRequestedEvent event) {
        log.error("💀 Document landed in DLQ after all retries: " +
                  "document={} file='{}' workspace={}",
                event.documentId(), event.filename(), event.workspaceId());
        // Could notify an admin, write to an alert table, send email, etc.
        // For now we just log — this is observable via Grafana alert on DLQ depth.
    }
}
