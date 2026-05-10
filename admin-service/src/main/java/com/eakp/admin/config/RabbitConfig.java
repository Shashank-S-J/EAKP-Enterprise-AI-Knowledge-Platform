package com.eakp.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.Map;

@Configuration
public class RabbitConfig {

    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf,
                                          Jackson2JsonMessageConverter converter) {
        RabbitTemplate t = new RabbitTemplate(cf);
        t.setMessageConverter(converter);
        return t;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf, Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(converter);
        return factory;
    }

    // Admin listens on the notify queue to track ingestion completions
    @Bean
    public Queue adminNotifyQueue() {
        return QueueBuilder.durable("eakp.admin.notify.queue").build();
    }

    @Bean
    public TopicExchange ingestionExchange() {
        return ExchangeBuilder.topicExchange("eakp.ingestion").durable(true).build();
    }

    @Bean
    public Binding adminNotifyBinding(Queue adminNotifyQueue,
                                       TopicExchange ingestionExchange) {
        return BindingBuilder.bind(adminNotifyQueue)
                .to(ingestionExchange).with("notify.ready");
    }
}

// ── Ingestion completion listener ─────────────────────────────────────────────

@Component
@RequiredArgsConstructor
@Slf4j
class IngestionCompletionListener {

    @RabbitListener(queues = "eakp.admin.notify.queue")
    public void onIngestionCompleted(Map<String, Object> event) {
        String status      = (String) event.get("status");
        String documentId  = (String) event.get("documentId");
        String workspaceId = (String) event.get("workspaceId");
        Object chunkCount  = event.get("chunkCount");

        if ("READY".equals(status)) {
            log.info("✅ Ingestion complete: doc={} chunks={} ws={}",
                    documentId, chunkCount, workspaceId);
        } else {
            log.error("❌ Ingestion failed: doc={} error={} ws={}",
                    documentId, event.get("errorMessage"), workspaceId);
        }
        // Future: persist to audit log table, send WebSocket push to UI
    }
}
