package com.eakp.chat.service;

import com.eakp.chat.model.Conversation;
import com.eakp.chat.model.Message;
import com.eakp.chat.repository.ConversationRepository;
import com.eakp.chat.repository.MessageRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Async summarisation helper to avoid Spring AOP self-invocation issues.
 * Uses programmatic CircuitBreaker (not annotation) since summarise()
 * is called internally from maybeSummariseAsync().
 */
@Service
@Slf4j
public class ConversationSummarisationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository      messageRepository;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ChatClient guardClient;

    public ConversationSummarisationService(ConversationRepository conversationRepository,
                                             MessageRepository messageRepository,
                                             CircuitBreakerRegistry circuitBreakerRegistry,
                                             @Qualifier("guardChatClient") ChatClient guardClient) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.guardClient = guardClient;
    }

    @Value("${app.rag.max-conversation-messages:10}")
    private int maxMessages;

    @Value("${app.rag.summarize-after-messages:8}")
    private int summarizeThreshold;

    private CircuitBreaker circuitBreaker;

    @PostConstruct
    void init() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("llmSummarise");
    }

    @Async
    @Transactional
    public void maybeSummariseAsync(UUID conversationId) {
        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null) return;

        long count = messageRepository.countByConversationId(conv.getId());
        if (count <= summarizeThreshold) return;

        List<Message> all = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conv.getId());
        List<Message> toSummarise = all.subList(
                0, all.size() - (maxMessages / 2));

        String summary = summarise(toSummarise);
        conv.setSummary(summary);
        conversationRepository.save(conv);
        log.info("Summarised {} messages for conversation {}",
                toSummarise.size(), conv.getId());
    }

    /**
     * Summarise messages using the LLM, wrapped in a circuit breaker.
     */
    public String summarise(List<Message> messages) {
        if (messages.isEmpty()) return "";

        try {
            return circuitBreaker.executeSupplier(() -> {
                String history = formatMessages(messages);
                String prompt = """
                    Summarise the following conversation in 3-5 sentences.
                    Capture the main topics, questions asked, and key answers.
                    Be concise and factual.

                    CONVERSATION:
                    %s

                    SUMMARY:
                    """.formatted(history);

                return guardClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
            });
        } catch (Exception e) {
            log.warn("Summarisation failed (circuit breaker or LLM): {}", e.getMessage());
            String history = formatMessages(messages);
            return history.length() > 500
                    ? history.substring(0, 500) + "..." : history;
        }
    }

    private String formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }
}
