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
        rabbitTemplate.convertAndSend(exchange, ingestRoutingKey, event);
    }

    public void publishIngestionCompleted(IngestionCompletedEvent event) {
        log.info("Publishing ingestion complete: document={} status={}",
                event.documentId(), event.status());
        rabbitTemplate.convertAndSend(exchange, notifyRoutingKey, event);
    }
}
