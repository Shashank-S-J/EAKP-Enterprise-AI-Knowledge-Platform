package com.eakp.ingestion.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class IngestionEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.routing-key.ingest}")
    private String ingestRoutingKey;

    @Value("${app.rabbitmq.routing-key.notify}")
    private String notifyRoutingKey;

    public void publishIngestionRequest(IngestionRequestedEvent event) {
        log.info("Publishing ingestion request: document={} workspace={}",
                event.documentId(), event.workspaceId());
        try {
            rabbitTemplate.convertAndSend(exchange, ingestRoutingKey, event);
        } catch (Exception e) {
            log.warn("RabbitMQ publish failed (broker unreachable?): {}. " +
                    "This is non-fatal because ingestion now runs synchronously.", e.getMessage());
        }
    }

    public void publishIngestionCompleted(IngestionCompletedEvent event) {
        log.info("Publishing ingestion complete: document={} status={}",
                event.documentId(), event.status());
        rabbitTemplate.convertAndSend(exchange, notifyRoutingKey, event);
    }
}