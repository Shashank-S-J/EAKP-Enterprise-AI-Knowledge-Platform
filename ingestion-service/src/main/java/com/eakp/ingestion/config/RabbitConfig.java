package com.eakp.ingestion.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Value("${app.rabbitmq.exchange}")
    private String exchange;

    @Value("${app.rabbitmq.queue.ingest}")
    private String ingestQueue;

    @Value("${app.rabbitmq.queue.ingest-dlq}")
    private String ingestDlq;

    @Value("${app.rabbitmq.queue.notify}")
    private String notifyQueue;

    @Value("${app.rabbitmq.routing-key.ingest}")
    private String ingestRoutingKey;

    @Value("${app.rabbitmq.routing-key.notify}")
    private String notifyRoutingKey;

    // ── Exchange ──────────────────────────────────────────────────────────────

    @Bean
    public TopicExchange ingestionExchange() {
        return ExchangeBuilder.topicExchange(exchange)
                .durable(true)
                .build();
    }

    // ── Dead Letter Queue ─────────────────────────────────────────────────────

    @Bean
    public Queue ingestDeadLetterQueue() {
        return QueueBuilder.durable(ingestDlq).build();
    }

    @Bean
    public DirectExchange dlqExchange() {
        return new DirectExchange("eakp.dlq.exchange");
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder.bind(ingestDeadLetterQueue())
                .to(dlqExchange())
                .with(ingestDlq);
    }

    // ── Ingest Queue ──────────────────────────────────────────────────────────

    @Bean
    public Queue ingestQueue() {
        return QueueBuilder.durable(ingestQueue)
                .withArgument("x-dead-letter-exchange", "eakp.dlq.exchange")
                .withArgument("x-dead-letter-routing-key", ingestDlq)
                .withArgument("x-message-ttl", 3_600_000)  // 1 hour
                .build();
    }

    @Bean
    public Binding ingestBinding(Queue ingestQueue, TopicExchange ingestionExchange) {
        return BindingBuilder.bind(ingestQueue)
                .to(ingestionExchange)
                .with(ingestRoutingKey);
    }

    // ── Notify Queue ──────────────────────────────────────────────────────────

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(notifyQueue).build();
    }

    @Bean
    public Binding notifyBinding(Queue notifyQueue, TopicExchange ingestionExchange) {
        return BindingBuilder.bind(notifyQueue)
                .to(ingestionExchange)
                .with(notifyRoutingKey);
    }

    // ── JSON serialisation ────────────────────────────────────────────────────

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory cf) {
        RabbitTemplate template = new RabbitTemplate(cf);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(cf);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setDefaultRequeueRejected(false); // send to DLQ on failure
        return factory;
    }
}
