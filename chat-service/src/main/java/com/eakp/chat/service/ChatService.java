package com.eakp.chat.service;

import com.eakp.chat.model.Message;
import com.eakp.chat.service.HallucinationGuardService.GroundingResult;
import com.eakp.chat.service.RagPipelineService.RetrievedChunk;
import com.eakp.chat.service.SemanticCacheService.CachedAnswer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * ChatService — main orchestrator.
 *
 * Execution path for a new question (cache miss):
 *   1. Rewrite query        → resolve pronouns/references using conversation history
 *   2. Check semantic cache → if hit, return immediately
 *   3. Retrieve context     → RAG pipeline (hybrid search + rerank)
 *   4. Load history         → conversation memory
 *   5. Build prompt         → system + context + history
 *   6. Stream LLM response  → SSE token-by-token
 *   7. Post-process         → hallucination guard, cache write, DB persist
 *   8. Record metrics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatClient                  chatClient;
    private final RagPipelineService          ragPipeline;
    private final SemanticCacheService        semanticCache;
    private final ConversationMemoryService   memoryService;
    private final HallucinationGuardService   hallucinationGuard;
    private final AiMetricsService            metrics;
    private final QueryRewriteService         queryRewriter;

    @Value("${app.rag.max-context-chars:8000}")
    private int maxContextChars;

    @Value("${app.rag.low-confidence-threshold:0.6}")
    private double lowConfidenceThreshold;

    // ── Public streaming API ──────────────────────────────────────────────────

    /**
     * Stream an answer to a user question.
     *
     * @param query          The user's question
     * @param conversationId Conversation UUID (for history)
     * @param workspaceId    Workspace UUID (for tenant isolation)
     * @param userId         User UUID (for message persistence)
     * @return Flux of SSE-ready strings: tokens, then a [DONE] marker
     */
    public Flux<String> streamAnswer(String query,
                                      UUID   conversationId,
                                      UUID   workspaceId,
                                      UUID   userId) {

        Instant start = Instant.now();

        // 1. Rewrite query for multi-turn clarity (resolve "it", "that", etc.)
        String rewrittenQuery;
        try {
            rewrittenQuery = queryRewriter.rewrite(query, conversationId, workspaceId);
        } catch (Exception e) {
            log.debug("Query rewrite skipped: {}", e.getMessage());
            rewrittenQuery = query;
        }
        final String effectiveQuery = rewrittenQuery;

        // 2. Check semantic cache (fast pgvector cosine lookup)
        Optional<CachedAnswer> cached =
                semanticCache.findSimilar(effectiveQuery, workspaceId);

        if (cached.isPresent()) {
            metrics.recordCacheHit();
            log.info("Cache HIT for ws={}", workspaceId);
            CachedAnswer answer = cached.get();

            // Persist the message even on cache hit for conversation history
            persistMessagesAsync(conversationId, workspaceId,
                    query, answer.answer(), List.of(), 1.0);

            return Flux.fromIterable(splitIntoTokens(answer.answer()))
                    .concatWith(Flux.just("[DONE]"));
        }

        metrics.recordCacheMiss();

        // 3 + 4: Retrieve context and history
        return Mono.fromCallable(() -> {
                    List<RetrievedChunk> context =
                            ragPipeline.retrieve(effectiveQuery, workspaceId);
                    metrics.recordChunksRetrieved(context.size());
                    return context;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(context -> {
                    // Load conversation history
                    String history = "";
                    try {
                        history = memoryService.buildHistoryPrompt(
                                conversationId, workspaceId);
                    } catch (Exception e) {
                        log.warn("Could not load history: {}", e.getMessage());
                    }

                    // Limit context size to prevent LLM truncation
                    List<RetrievedChunk> trimmedContext = trimContext(context);

                    // Build the prompt
                    String systemPrompt = buildSystemPrompt(trimmedContext);
                    String userMessage  = buildUserMessage(effectiveQuery, history);

                    final List<RetrievedChunk> capturedContext   = trimmedContext;
                    final AtomicReference<StringBuilder> buffer  =
                            new AtomicReference<>(new StringBuilder());

                    Instant llmStart = Instant.now();

                    // 5. Stream the LLM response
                    return chatClient.prompt()
                            .system(systemPrompt)
                            .user(userMessage)
                            .stream()
                            .content()
                            .doOnNext(token ->
                                    buffer.get().append(token))
                            .doOnComplete(() -> {
                                String fullAnswer = buffer.get().toString();

                                metrics.recordLlmLatency(
                                        Duration.between(llmStart, Instant.now()));
                                metrics.recordPipelineLatency(
                                        Duration.between(start, Instant.now()));

                                Mono.fromRunnable(() ->
                                    postProcess(query, effectiveQuery, workspaceId,
                                            conversationId, userId,
                                            fullAnswer, capturedContext))
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe();
                            })
                            .doOnError(e ->
                                    log.error("Streaming error for ws={}", workspaceId, e))
                            .concatWith(Flux.just("[DONE]"));
                });
    }

    // ── Context trimming to fit LLM window ────────────────────────────────────

    /**
     * Trim context chunks to fit within maxContextChars.
     * Keeps highest-relevance chunks first.
     */
    private List<RetrievedChunk> trimContext(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> trimmed = new ArrayList<>();
        int totalChars = 0;
        for (RetrievedChunk chunk : chunks) {
            if (totalChars + chunk.content().length() > maxContextChars) {
                // Include partial last chunk if space allows
                int remaining = maxContextChars - totalChars;
                if (remaining > 100) {
                    trimmed.add(new RetrievedChunk(
                            chunk.id(),
                            chunk.content().substring(0, remaining) + "...",
                            chunk.source(),
                            chunk.documentId(),
                            chunk.relevanceScore()));
                }
                break;
            }
            trimmed.add(chunk);
            totalChars += chunk.content().length();
        }
        if (trimmed.size() < chunks.size()) {
            log.debug("Trimmed context from {} to {} chunks ({}→{} chars)",
                    chunks.size(), trimmed.size(),
                    chunks.stream().mapToInt(c -> c.content().length()).sum(), totalChars);
        }
        return trimmed;
    }

    // ── Post-processing (async, off streaming thread) ─────────────────────────

    private void postProcess(String originalQuery,
                              String effectiveQuery,
                              UUID   workspaceId,
                              UUID   conversationId,
                              UUID   userId,
                              String answer,
                              List<RetrievedChunk> context) {
        try {
            // Hallucination guard
            GroundingResult grounding =
                    hallucinationGuard.check(answer, context);
            metrics.recordFaithfulness(grounding.confidence());

            if (!grounding.grounded()) {
                log.warn("Ungrounded answer detected ws={} claims={}",
                        workspaceId, grounding.unsupportedClaims());
            }

            // Semantic cache write (only cache high-confidence answers)
            if (grounding.confidence() >= lowConfidenceThreshold) {
                List<String> sources = context.stream()
                        .map(RetrievedChunk::source)
                        .distinct()
                        .collect(Collectors.toList());
                semanticCache.store(effectiveQuery, workspaceId, answer, sources);
            } else {
                log.info("Skipping cache for low-confidence answer ({})",
                        grounding.confidence());
            }

            // Persist messages to DB
            List<Message.SourceReference> sourceRefs = context.stream()
                    .map(c -> new Message.SourceReference(
                            c.id(),
                            c.content().length() > 200
                                    ? c.content().substring(0, 200) + "..."
                                    : c.content(),
                            c.source()))
                    .collect(Collectors.toList());

            persistMessagesAsync(conversationId, workspaceId,
                    originalQuery, answer, sourceRefs, grounding.confidence());

        } catch (Exception e) {
            log.error("Post-processing error for ws={}", workspaceId, e);
        }
    }

    private void persistMessagesAsync(UUID   conversationId,
                                       UUID   workspaceId,
                                       String userQuery,
                                       String assistantAnswer,
                                       List<Message.SourceReference> sources,
                                       double faithfulness) {
        try {
            memoryService.appendUserMessage(
                    conversationId, workspaceId, userQuery);
            memoryService.appendAssistantMessage(
                    conversationId, workspaceId,
                    assistantAnswer, sources, faithfulness);
        } catch (Exception e) {
            log.warn("Failed to persist messages: {}", e.getMessage());
        }
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private String buildSystemPrompt(List<RetrievedChunk> context) {
        if (context.isEmpty()) {
            return """
                You are a helpful assistant. No documents have been uploaded
                to this workspace yet. Politely inform the user and suggest
                they upload documents first.
                """;
        }

        String contextBlock = context.stream()
                .map(RetrievedChunk::toPromptString)
                .collect(Collectors.joining("\n\n---\n\n"));

        return """
            You are an expert knowledge assistant that answers questions using
            ONLY the provided CONTEXT below. You are precise and thorough.

            Rules:
            1. Answer based SOLELY on the CONTEXT. Never use outside knowledge.
            2. Cite EVERY factual claim with [Source: filename] immediately after the claim.
            3. If the answer is partially in the context, answer what you can and explicitly
               state what information is missing.
            4. If the answer is NOT in the context at all, respond EXACTLY with:
               "I don't have enough information to answer that based on the uploaded documents."
            5. Be concise and precise. Avoid repeating the question.
            6. Use bullet points for multi-part answers.
            7. If multiple sources contain conflicting information, note the discrepancy.
            8. Do NOT speculate, infer, or extrapolate beyond what is explicitly stated.

            CONTEXT (%d chunks, %d characters):
            %s
            """.formatted(context.size(),
                context.stream().mapToInt(c -> c.content().length()).sum(),
                contextBlock);
    }

    private String buildUserMessage(String query, String history) {
        if (history == null || history.isBlank()) {
            return query;
        }
        return """
            %s

            Current question: %s
            """.formatted(history, query);
    }

    /**
     * Split a full answer into pseudo-tokens for cache-hit streaming.
     * Streams word-by-word to mimic real token streaming.
     */
    private List<String> splitIntoTokens(String text) {
        List<String> tokens = new ArrayList<>();
        String[] words = text.split("(?<=\\s)|(?=\\s)");
        for (String w : words) {
            if (!w.isEmpty()) tokens.add(w);
        }
        return tokens;
    }
}
