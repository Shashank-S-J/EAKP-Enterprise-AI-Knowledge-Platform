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
 * 1. Rewrite query → resolve pronouns/references using conversation history
 * 2. Check semantic cache → if hit, return immediately
 * 3. Retrieve context → RAG pipeline (hybrid search + rerank)
 * 4. Load history → conversation memory
 * 5. Build prompt → system + context + history
 * 6. Stream LLM response → SSE token-by-token
 * 7. Post-process → hallucination guard, cache write, DB persist
 * 8. Record metrics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    private final ChatClient chatClient;
    private final RagPipelineService ragPipeline;
    private final SemanticCacheService semanticCache;
    private final ConversationMemoryService memoryService;
    private final HallucinationGuardService hallucinationGuard;
    private final AiMetricsService metrics;
    private final QueryRewriteService queryRewriter;
    private final AttachedDocumentService attachedDocuments;
    private final CrossConversationSearchService crossConvSearch;

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
                                     UUID conversationId,
                                     UUID workspaceId,
                                     UUID userId) {

        final Instant start = Instant.now();

        // Wrap everything in a deferred Flux so blocking calls don't throw 500s
        return Flux.defer(() -> {
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
            Optional<CachedAnswer> cached;
            try {
                cached = semanticCache.findSimilar(effectiveQuery, workspaceId);
            } catch (Exception e) {
                log.warn("Semantic cache lookup failed: {}", e.getMessage());
                cached = Optional.empty();
            }

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
                        List<RetrievedChunk> context = ragPipeline.retrieve(effectiveQuery, workspaceId, conversationId);
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

                        // Files explicitly attached to this conversation — lets the LLM
                        // resolve references like "the resume" / "that PDF" without
                        // needing the user to repeat the filename.
                        List<String> attachedFiles = attachedDocuments.attachedFilenames(conversationId, workspaceId);

                        // Detect documents the user JUST attached that have not
                        // finished ingestion yet. If context is empty AND there
                        // are pending docs, we tell the user it's still processing
                        // rather than the generic "no info" reply.
                        List<String> pendingFiles =
                                attachedDocuments.pendingAttachedFilenames(conversationId, workspaceId);

                        // Documents from OTHER chats that the retrieval pipeline
                        // surfaced as relevant. The LLM uses these to proactively
                        // suggest "I also have <X> on this topic — want me to use it?".
                        java.util.Set<String> attachedSet = new java.util.HashSet<>(attachedFiles);
                        java.util.List<String> relatedFiles = trimmedContext.stream()
                                .map(RetrievedChunk::source)
                                .filter(java.util.Objects::nonNull)
                                .filter(s -> !"unknown".equals(s))
                                .filter(s -> !attachedSet.contains(s))
                                .distinct()
                                .toList();

                        // Cross-conversation memory: snippets from earlier chats in
                        // the same workspace. Powers "summarize yesterday's discussion".
                        java.util.List<CrossConversationSearchService.PastMessage> pastMessages = crossConvSearch
                                .findRelevantPastMessages(
                                        effectiveQuery, workspaceId, conversationId);

                        // Build the prompt
                        String systemPrompt = buildSystemPrompt(
                                trimmedContext, attachedFiles, relatedFiles,
                                pastMessages, pendingFiles);
                        String userMessage = buildUserMessage(effectiveQuery, history);

                        final List<RetrievedChunk> capturedContext = trimmedContext;
                        final AtomicReference<StringBuilder> buffer = new AtomicReference<>(new StringBuilder());

                        Instant llmStart = Instant.now();

                        // 5. Stream the LLM response
                        return chatClient.prompt()
                                .system(systemPrompt)
                                .user(userMessage)
                                .stream()
                                .content()
                                .doOnNext(token -> buffer.get().append(token))
                                .doOnComplete(() -> {
                                    String fullAnswer = buffer.get().toString();

                                    metrics.recordLlmLatency(
                                            Duration.between(llmStart, Instant.now()));
                                    metrics.recordPipelineLatency(
                                            Duration.between(start, Instant.now()));

                                    Mono.fromRunnable(() -> postProcess(query, effectiveQuery, workspaceId,
                                                    conversationId, userId,
                                                    fullAnswer, capturedContext))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .subscribe();
                                })
                                .doOnError(e -> log.error("Streaming error for ws={}", workspaceId, e))
                                .concatWith(Flux.just("[DONE]"));
                    });
        }).onErrorResume(e -> {
            log.error("Chat stream error for ws={}", workspaceId, e);
            return Flux.just("[ERROR] " + e.getMessage(), "[DONE]");
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
                             UUID workspaceId,
                             UUID conversationId,
                             UUID userId,
                             String answer,
                             List<RetrievedChunk> context) {
        try {
            // Hallucination guard
            GroundingResult grounding = hallucinationGuard.check(answer, context);
            metrics.recordFaithfulness(grounding.confidence());

            if (!grounding.grounded()) {
                log.warn("Ungrounded answer detected ws={} claims={}",
                        workspaceId, grounding.unsupportedClaims());
            }

            // Semantic cache write — only when we had real context AND
            // the answer is high-confidence. Caching empty-context replies
            // poisons future lookups with "no documents" boilerplate.
            if (!context.isEmpty() && grounding.confidence() >= lowConfidenceThreshold) {
                List<String> sources = context.stream()
                        .map(RetrievedChunk::source)
                        .distinct()
                        .collect(Collectors.toList());
                semanticCache.store(effectiveQuery, workspaceId, answer, sources);
            } else {
                log.info("Skipping cache (context={}, confidence={})",
                        context.size(), grounding.confidence());
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

    private void persistMessagesAsync(UUID conversationId,
                                      UUID workspaceId,
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

    private String buildSystemPrompt(List<RetrievedChunk> context,
                                     List<String> attachedFiles,
                                     List<String> relatedFiles,
                                     List<CrossConversationSearchService.PastMessage> pastMessages,
                                     List<String> pendingFiles) {
        boolean hasContext = !context.isEmpty();
        boolean hasPast = pastMessages != null && !pastMessages.isEmpty();
        boolean hasPending = pendingFiles != null && !pendingFiles.isEmpty();

        if (!hasContext && !hasPast) {
            if (hasPending) {
                // The user just uploaded a file and is asking about it before
                // ingestion finished. Give a specific, friendly reply.
                String list = pendingFiles.stream()
                        .map(f -> "\"" + f + "\"")
                        .collect(Collectors.joining(", "));
                return """
                        You are a knowledge assistant. The user just attached a
                        file (%s) that is still being processed. Respond with
                        EXACTLY this sentence and nothing else:

                        "Your file %s is still being processed — this usually \
                        takes about 10–20 seconds. Please ask your question \
                        again in a moment."
                        """.formatted(list, list);
            }
            // Keep this short and factual — never invent metaphors or
            // multi-paragraph explanations. The user just needs to know
            // the retrieval pipeline returned no relevant chunks.
            return """
                    You are a knowledge assistant. No relevant document content
                    was retrieved for this question. Respond with exactly this
                    sentence and nothing else:

                    "I couldn't find anything relevant in the uploaded documents \
                    for that question. If you just uploaded a file, please wait a \
                    few seconds for ingestion to finish and try again, or rephrase \
                    your question."
                    """;
        }

        String contextBlock = hasContext
                ? context.stream()
                .map(RetrievedChunk::toPromptString)
                .collect(Collectors.joining("\n\n---\n\n"))
                : "(no document chunks retrieved for this question)";

        // When the user attached files inline (ChatGPT-style), tell the LLM
        // their filenames so it can resolve casual references ("the resume",
        // "that PDF", "summarize this", "explain it") without needing exact
        // names. The first file in the list is the "current" attachment
        // (most-recent upload) and is what bare demonstratives like "this"
        // / "it" / "the file" refer to.
        String attachedBlock = (attachedFiles == null || attachedFiles.isEmpty())
                ? ""
                : """
                        ATTACHED TO THIS CONVERSATION (most recent first). Treat \
                        bare references like "this", "that", "it", "the file", \
                        "the document", "this PDF", "summarize this", "explain \
                        it", "what does this say" as referring to the FIRST file \
                        in this list. Phrases like "the resume" / "the spec" / \
                        "the contract" match by filename:
                        %s

                        """.formatted(attachedFiles.stream()
                .map(f -> "  - " + f)
                .collect(Collectors.joining("\n")));

        // Documents from elsewhere in the workspace that look relevant. The
        // LLM should mention these proactively (Rule 9) rather than silently
        // using them — mimics Claude's "I notice you have <doc>, want me to
        // include it?" behaviour.
        String relatedBlock = (relatedFiles == null || relatedFiles.isEmpty())
                ? ""
                : """
                        POTENTIALLY RELATED DOCUMENTS (uploaded earlier in OTHER \
                        chats, not currently attached — only mention if directly \
                        relevant to the user's question):
                        %s

                        """.formatted(relatedFiles.stream()
                .map(f -> "  - " + f)
                .collect(Collectors.joining("\n")));

        // Snippets from earlier conversations (Postgres FTS), so queries like
        // "summarize yesterday's discussion" or "the issue we talked about
        // last week" can ground on real past content rather than guessing.
        String pastBlock = (!hasPast)
                ? ""
                : """
                        RECENT RELATED CONVERSATIONS (snippets from this \
                        workspace's earlier chats, newest first):
                        %s

                        """.formatted(pastMessages.stream()
                .map(CrossConversationSearchService.PastMessage::toPromptLine)
                .collect(Collectors.joining("\n")));

        return """
                You are an expert knowledge assistant for this workspace. You
                answer using ONLY the CONTEXT, RELATED DOCUMENTS, and RECENT
                RELATED CONVERSATIONS provided below. You are precise and
                thorough.

                %s%s%sRules:
                1. Answer based SOLELY on the material above. Never use outside knowledge.
                2. Cite EVERY factual claim with [Source: filename] or, when quoting
                   a past conversation, [Source: chat "<title>", <date>].
                3. If the user references something with vague language ("the resume",
                   "that PDF", "yesterday's discussion", "the doc we talked about"),
                   resolve it against the ATTACHED / RELATED / RECENT sections.
                4. If the answer is partially supported, answer what you can and
                   explicitly state what is missing.
                5. If the answer is NOT supported at all, respond EXACTLY with:
                   "I don't have enough information to answer that based on the
                    uploaded documents or earlier conversations."
                6. Be concise. Use bullet points for multi-part answers. Avoid
                   repeating the question and avoid metaphors/analogies.
                7. If multiple sources conflict, note the discrepancy.
                8. Do NOT speculate, infer, or extrapolate beyond what is stated.
                9. If you find a POTENTIALLY RELATED DOCUMENT that looks directly
                   relevant but is not currently attached, finish your answer with
                   one line: "I also have <filename> on this topic — want me to
                   include it?".

                CONTEXT (%d chunks, %d characters):
                %s
                """.formatted(attachedBlock, relatedBlock, pastBlock,
                context.size(),
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
            if (!w.isEmpty())
                tokens.add(w);
        }
        return tokens;
    }
}