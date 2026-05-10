package com.eakp.chat.service;

import com.eakp.chat.model.Message;
import com.eakp.chat.repository.MessageRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Rewrites user queries to be self-contained by resolving pronouns,
 * references ("it", "that", "the second one"), and implicit context
 * from conversation history.
 *
 * This dramatically improves retrieval accuracy for multi-turn conversations
 * where queries like "what about its pricing?" would otherwise fail to
 * retrieve relevant chunks without knowing what "it" refers to.
 *
 * Example:
 *   History: USER: "Tell me about Kubernetes" → ASSISTANT: "Kubernetes is..."
 *   Query:   "What about its networking model?"
 *   Rewrite: "What is the networking model in Kubernetes?"
 */
@Service
@Slf4j
public class QueryRewriteService {

    private final ChatClient guardClient;
    private final MessageRepository messageRepository;

    public QueryRewriteService(@Qualifier("guardChatClient") ChatClient guardClient,
                                MessageRepository messageRepository) {
        this.guardClient = guardClient;
        this.messageRepository = messageRepository;
    }

    @Value("${app.rag.query-rewrite-enabled:true}")
    private boolean enabled;

    @Value("${app.rag.query-rewrite-history-messages:4}")
    private int historyMessageCount;

    /**
     * Rewrite the query to be self-contained using recent conversation history.
     * If the query is already self-contained (no pronouns, no references),
     * returns it unchanged.
     *
     * Falls back to the original query on any failure (fail-open).
     */
    @CircuitBreaker(name = "llmRewrite", fallbackMethod = "rewriteFallback")
    public String rewrite(String query, UUID conversationId, UUID workspaceId) {
        if (!enabled) return query;

        // Skip rewrite for first message or self-contained queries
        if (isSelfContained(query)) {
            log.debug("Query is self-contained, skipping rewrite: '{}'", query);
            return query;
        }

        // Load recent messages for context
        List<Message> recent = messageRepository
                .findTopNByConversationIdOrderByCreatedAtDesc(
                        conversationId, historyMessageCount);

        if (recent.isEmpty()) {
            return query; // First message, no rewrite needed
        }

        // Build rewrite prompt
        StringBuilder historyBlock = new StringBuilder();
        // Messages come newest-first, reverse for chronological order
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message m = recent.get(i);
            historyBlock.append(m.getRole()).append(": ")
                    .append(truncate(m.getContent(), 200)).append("\n");
        }

        String prompt = """
            Given the conversation history and the latest user query,
            rewrite the query to be fully self-contained and specific.
            Resolve all pronouns (it, they, that, this) and implicit references.
            If the query is already self-contained, return it unchanged.
            
            Return ONLY the rewritten query, nothing else. No explanation.
            
            CONVERSATION HISTORY:
            %s
            
            LATEST QUERY: %s
            
            REWRITTEN QUERY:
            """.formatted(historyBlock.toString(), query);

        String rewritten = guardClient.prompt()
                .user(prompt)
                .call()
                .content();

        if (rewritten == null || rewritten.isBlank()) {
            return query;
        }

        // Clean up: remove quotes, trailing whitespace
        rewritten = rewritten.trim()
                .replaceAll("^[\"']|[\"']$", "")
                .trim();

        // Safety: if rewrite is drastically different (hallucinated), use original
        if (rewritten.length() > query.length() * 3) {
            log.warn("Query rewrite too long, using original. Rewritten: '{}'", rewritten);
            return query;
        }

        log.info("Query rewritten: '{}' → '{}'", query, rewritten);
        return rewritten;
    }

    @SuppressWarnings("unused")
    private String rewriteFallback(String query, UUID conversationId,
                                    UUID workspaceId, Throwable t) {
        log.debug("Query rewrite fallback (circuit breaker): {}", t.getMessage());
        return query;
    }

    /**
     * Heuristic: a query is likely self-contained if it doesn't contain
     * common pronouns/references that need resolution.
     */
    private boolean isSelfContained(String query) {
        if (query == null || query.length() > 200) return true; // Long queries are usually specific
        String lower = query.toLowerCase();
        // Check for common anaphoric references
        String[] indicators = {"\\bit\\b", "\\bits\\b", "\\bthat\\b", "\\bthis\\b",
                "\\bthese\\b", "\\bthose\\b", "\\bthem\\b", "\\btheir\\b",
                "the same", "the above", "the previous", "mentioned",
                "the first", "the second", "the last"};
        for (String pattern : indicators) {
            if (lower.matches(".*" + pattern + ".*")) {
                return false;
            }
        }
        return true;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}

