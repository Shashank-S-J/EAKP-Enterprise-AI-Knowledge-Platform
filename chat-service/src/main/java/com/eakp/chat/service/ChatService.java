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

            // 2. Check semantic cache (fast pgvector cosine lookup).
            //
            // We skip the cache whenever the conversation has any attached
            // documents — the cache is keyed only by (workspace, query
            // embedding), so a same-looking query against a different file
            // set would happily return the wrong cached answer. Re-generating
            // a few extra times is cheap; serving the wrong answer is not.
            Optional<CachedAnswer> cached;
            boolean hasAttachments = !attachedDocuments
                    .attachedDocumentIds(conversationId, workspaceId).isEmpty();
            try {
                cached = hasAttachments
                        ? Optional.empty()
                        : semanticCache.findSimilar(effectiveQuery, workspaceId);
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

            // 3 + 4: Retrieve context and history.
            // Bare-reference focusing: if the user wrote a vague prompt like
            // "explain", "summarize this", "tldr", or "what does it say" right
            // after uploading a file, pin retrieval to that single most-recent
            // file so we don't accidentally answer about an older attachment.
            Optional<AttachedDocumentService.DocRef> mostRecentDoc =
                    attachedDocuments.mostRecentAttachment(conversationId, workspaceId);
            boolean bareReference = isBareDocReference(query);
            final UUID focusedDocId = (bareReference && mostRecentDoc.isPresent())
                    ? mostRecentDoc.get().id() : null;
            final String focusedFilename = (focusedDocId == null) ? null
                    : mostRecentDoc.get().filename();
            if (focusedDocId != null) {
                log.info("Bare-reference detected → focusing retrieval on most-recent doc '{}' ({})",
                        focusedFilename, focusedDocId);
            }

            return Mono.fromCallable(() -> {
                        List<RetrievedChunk> context = ragPipeline.retrieve(
                                effectiveQuery, workspaceId, conversationId, focusedDocId);
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

                        // Limit context size to prevent LLM truncation,
                        // then reorder for lost-in-the-middle (best first,
                        // second-best last). Reorder AFTER trimming so the
                        // budget calculation works on the rerank order.
                        List<RetrievedChunk> trimmedContext = reorderForEdges(trimContext(context));

                        // Files explicitly attached to this conversation — lets the LLM
                        // resolve references like "the resume" / "that PDF" without
                        // needing the user to repeat the filename. When we focused
                        // retrieval on a single doc, show ONLY that one so the LLM
                        // can't accidentally anchor on an older attachment.
                        List<String> attachedFiles = (focusedFilename != null)
                                ? List.of(focusedFilename)
                                : attachedDocuments.attachedFilenames(conversationId, workspaceId);

                        // Detect documents the user JUST attached that have not
                        // finished ingestion yet. If context is empty AND there
                        // are pending docs, we tell the user it's still processing
                        // rather than the generic "no info" reply.
                        List<String> pendingFiles =
                                attachedDocuments.pendingAttachedFilenames(conversationId, workspaceId);

                        // Documents from OTHER chats that the retrieval pipeline
                        // surfaced as relevant. The LLM uses these to proactively
                        // suggest "I also have <X> on this topic — want me to use it?".
                        //
                        // Suppress when the reader is clearly anchored to their
                        // current attachment(s):
                        //   • a bare/focused reference ("explain this") — they
                        //     don't care about other workspace docs
                        //   • a single file is attached — same reasoning
                        // In those cases mentioning an unrelated workspace doc
                        // (e.g. an architecture plan while they're reading a
                        // resume) reads as a non-sequitur, not a helpful nudge.
                        java.util.Set<String> attachedSet = new java.util.HashSet<>(attachedFiles);
                        boolean suppressRelated =
                                focusedFilename != null
                                        || (attachedFiles != null && attachedFiles.size() == 1);
                        java.util.List<String> relatedFiles = suppressRelated
                                ? java.util.List.of()
                                : trimmedContext.stream()
                                .map(RetrievedChunk::source)
                                .filter(java.util.Objects::nonNull)
                                .filter(s -> !"unknown".equals(s))
                                .filter(s -> !attachedSet.contains(s))
                                .distinct()
                                .toList();

                        // Cross-conversation memory: snippets from THIS USER's
                        // earlier chats in the same workspace. Scoped to the
                        // current user — other users' history is never read.
                        java.util.List<CrossConversationSearchService.PastMessage> pastMessages = crossConvSearch
                                .findRelevantPastMessages(
                                        effectiveQuery, workspaceId, userId, conversationId);

                        // Build the prompt
                        String systemPrompt = buildSystemPrompt(
                                trimmedContext, attachedFiles, relatedFiles,
                                pastMessages, pendingFiles, focusedFilename);
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
        });
        // Do NOT catch errors here with a fake "[ERROR] ..." token — the
        // controller's onErrorResume maps real exceptions to a proper
        // `event:error` SSE frame. Emitting them as text tokens caused the
        // error message to render *inside* the assistant's answer bubble.
    }

    // ── Context trimming to fit LLM window ────────────────────────────────────

    /**
     * Trim context chunks to fit within {@code maxContextChars}. We keep
     * complete chunks up to the budget; if a partial last chunk fits, we cut
     * at the nearest sentence/paragraph boundary (never mid-sentence,
     * never mid-citation), and only when the partial slice is at least
     * 200 chars — smaller fragments aren't worth the truncation noise.
     */
    private List<RetrievedChunk> trimContext(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> trimmed = new ArrayList<>();
        int totalChars = 0;
        for (RetrievedChunk chunk : chunks) {
            if (totalChars + chunk.content().length() > maxContextChars) {
                int remaining = maxContextChars - totalChars;
                if (remaining > 200) {
                    String slice = chunk.content().substring(0, remaining);
                    int cut = findSentenceBoundary(slice);
                    if (cut > 200) {
                        trimmed.add(new RetrievedChunk(
                                chunk.id(),
                                slice.substring(0, cut).trim() + " […]",
                                chunk.source(),
                                chunk.documentId(),
                                chunk.relevanceScore()));
                    }
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

    /**
     * Return the index of the last sentence/paragraph break in {@code text}.
     * Falls back to {@code text.length()} if nothing better is found. Never
     * returns inside a `[Source:` citation token so truncation can't damage
     * a citation marker.
     */
    private static int findSentenceBoundary(String text) {
        // Prefer paragraph break, then sentence terminators.
        int[] candidates = new int[] {
                text.lastIndexOf("\n\n"),
                text.lastIndexOf(". "),
                text.lastIndexOf("! "),
                text.lastIndexOf("? "),
                text.lastIndexOf("\n")
        };
        int best = -1;
        for (int c : candidates) {
            if (c > best) best = c;
        }
        if (best < 0) return text.length();
        int end = best + 1; // include the punctuation
        // If we'd land inside a `[Source: ...]` token, back up to before it.
        int openCite = text.lastIndexOf("[Source:", end);
        int closeCite = text.indexOf(']', openCite);
        if (openCite >= 0 && closeCite > end) {
            // The citation spans our cut point — rewind to before the open.
            end = openCite;
        }
        return Math.max(end, 0);
    }

    /**
     * Reorder a list of reranked chunks so the highest-relevance chunk is
     * FIRST and the second-highest is LAST. Mid-relevance chunks fill the
     * middle. This counters the well-documented "lost in the middle" failure
     * mode where LLMs disproportionately attend to the edges of a long
     * context window.
     *
     * <p>For lists of ≤2 items the original order is returned unchanged.</p>
     */
    private static List<RetrievedChunk> reorderForEdges(List<RetrievedChunk> ranked) {
        int n = ranked.size();
        if (n <= 2) return ranked;
        List<RetrievedChunk> out = new ArrayList<>(n);
        out.add(ranked.get(0));                  // strongest evidence first
        for (int i = 2; i < n; i++) out.add(ranked.get(i)); // middling middle
        out.add(ranked.get(1));                  // second-strongest last
        return out;
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

            boolean lowConfidence = !grounding.grounded()
                    || grounding.confidence() < lowConfidenceThreshold;

            if (lowConfidence) {
                log.warn("Low-confidence answer ws={} grounded={} confidence={} claims={}",
                        workspaceId, grounding.grounded(),
                        grounding.confidence(), grounding.unsupportedClaims());
            }

            // Persist sources first (used for both the cache write path AND
            // the message row) so the citation list stays consistent.
            List<Message.SourceReference> sourceRefs = context.stream()
                    .map(c -> new Message.SourceReference(
                            c.id(),
                            c.content().length() > 200
                                    ? c.content().substring(0, 200) + "..."
                                    : c.content(),
                            c.source()))
                    .collect(Collectors.toList());

            // Semantic cache write: only when we had real context AND the
            // answer is BOTH grounded and high-confidence. Caching a
            // low-confidence reply would replay a fabricated answer to
            // future users.
            if (!context.isEmpty() && grounding.grounded()
                    && grounding.confidence() >= lowConfidenceThreshold) {
                List<String> sources = context.stream()
                        .map(RetrievedChunk::source)
                        .distinct()
                        .collect(Collectors.toList());
                semanticCache.store(effectiveQuery, workspaceId, answer, sources);
            } else {
                log.info("Skipping cache (context={}, grounded={}, confidence={})",
                        context.size(), grounding.grounded(), grounding.confidence());
            }

            // If the answer was low-confidence, persist a transparent
            // version: the streamed text the user saw, plus an honest
            // disclaimer line so the message row reflects reality. The
            // `faithfulness` field already records the numeric score so the
            // frontend can flag low-confidence messages visually if desired.
            String persistedAnswer = lowConfidence
                    ? appendLowConfidenceNotice(answer, grounding)
                    : answer;

            persistMessagesAsync(conversationId, workspaceId,
                    originalQuery, persistedAnswer, sourceRefs,
                    grounding.confidence());

        } catch (Exception e) {
            log.error("Post-processing error for ws={}", workspaceId, e);
        }
    }

    /**
     * Append a single, honest disclaimer line to an answer the grounding
     * guard flagged as low-confidence. The streamed text the user already
     * saw is preserved verbatim — the disclaimer only changes what's stored
     * in conversation history (so retries and history-replay can't propagate
     * the unflagged version).
     */
    private static String appendLowConfidenceNotice(String answer, GroundingResult g) {
        if (answer == null) answer = "";
        String tail = answer.endsWith("\n") ? "" : "\n";
        return answer + tail
                + "\n_— Note: this answer may not be fully supported by the "
                + "uploaded documents. Please verify the specifics before "
                + "relying on it._";
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
                                     List<String> pendingFiles,
                                     String focusedFilename) {
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
                        You are EAKP, a friendly research librarian. The reader
                        just attached a file (%s) that is still being processed.
                        Respond with EXACTLY this sentence and nothing else:

                        "I'm still reading %s — give me about 10–20 seconds and \
                        ask me again. I'll have it ready then."
                        """.formatted(list, list);
            }
            // Keep this short and factual — never invent metaphors or
            // multi-paragraph explanations. The user just needs to know
            // the retrieval pipeline returned no relevant chunks.
            return """
                    You are EAKP, a friendly research librarian. Nothing in the
                    workspace matches this question. Respond with exactly this
                    sentence and nothing else:

                    "I couldn't find anything on that in the uploaded documents \
                    or earlier conversations. If you just uploaded a file, give \
                    me a few seconds to finish reading it — otherwise try \
                    rephrasing, or share a more specific keyword and I'll look \
                    again."
                    """;
        }

        String contextBlock;
        if (hasContext) {
            // Number every chunk so the LLM can cite by index ([1], [2], …)
            // and the reader can trace each claim back to a specific passage.
            // Each block shows: index, source filename, then the content.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < context.size(); i++) {
                RetrievedChunk c = context.get(i);
                if (i > 0) sb.append("\n\n---\n\n");
                sb.append("[").append(i + 1).append("] Source: ")
                        .append(c.source() == null ? "unknown" : c.source())
                        .append("\n")
                        .append(c.content());
            }
            contextBlock = sb.toString();
        } else {
            contextBlock = "(no document chunks retrieved for this question)";
        }

        // ATTACHED block. When we've focused on a single freshly-uploaded
        // file (bare reference like "explain" / "summarize this"), the block
        // is hard-pinned to that one file so the model can't drift to an
        // older attachment.
        boolean focused = focusedFilename != null;
        String attachedBlock;
        if (attachedFiles == null || attachedFiles.isEmpty()) {
            attachedBlock = "";
        } else if (focused) {
            attachedBlock = ("""
                    PRIMARY ATTACHMENT (the reader's current reference resolves \
                    to this file — answer about THIS file, not earlier ones):
                      - %s

                    """).formatted(focusedFilename);
        } else {
            attachedBlock = """
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
        }

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
                You are EAKP, a meticulous research librarian for this
                workspace. You guide the reader to the right shelf — calm,
                warm, and exact. You speak like a knowledgeable human:
                approachable, never robotic, never showy. You explain things
                so a busy non-expert understands them on the first read.

                %s%s%sHOW TO ANSWER
                • Lead with the answer. The first line gives the reader what
                  they asked for — no preamble, no restating the question.
                • Then expand. Use short paragraphs (2–4 sentences) or a tight
                  bulleted list when there are more than two parallel points.
                • Define jargon the first time it appears, in plain language.
                  Use everyday words by default; reach for the technical term
                  only when it adds precision.
                • Quote sparingly. Paraphrase in your own voice; reserve direct
                  quotes for definitions, numbers, or wording that matters.
                • Keep the tone helpful and human. Not "Per the document…" —
                  more like "The contract says…" or "According to section 4…".
                • When useful, close with one short pointer: "Want me to pull
                  the exact clause?" or "I can also compare this with <other
                  file> if helpful." One line, optional, never forced.

                GROUNDING RULES (these are firm)
                1. Answer using ONLY the CONTEXT, ATTACHED, RELATED, and RECENT
                   sections above. Do not bring in outside knowledge or your
                   training data.
                2. Cite every factual claim with the chunk index that supports
                   it, e.g. "the contract was signed on 14 March [3]". When a
                   filename is more natural for the reader, write
                   "[3, deal-memo.pdf]". For a past-chat citation use
                   [chat: "<title>", <date>]. Citations go at the end of the
                   sentence they support, not bunched at the end.
                3. Resolve vague references using the lists above. If the
                   PRIMARY ATTACHMENT block is present, the reader's "this" /
                   "it" / "explain" / "summarize" refers to THAT file, and your
                   answer must be about that file — even if other chunks slip
                   through.
                4. If the material only partially answers the question, answer
                   what's supported and clearly name what's missing.
                5. If nothing in the material supports the answer, reply
                   exactly: "I don't have enough information to answer that
                   based on the uploaded documents or earlier conversations."
                6. If two sources disagree, surface the disagreement plainly:
                   "Source A says X; Source B says Y."
                7. Don't speculate, infer hidden intent, or extrapolate past
                   what the text actually says.
                8. If — and only if — a POTENTIALLY RELATED DOCUMENT is on the
                   SAME topic the reader just asked about (not just present in
                   the workspace), end with one short line: "I also have
                   <filename> on this — want me to include it?" Otherwise stay
                   silent about it. Never write a disclaimer like "I don't
                   have information about <other file>" — if it's not
                   relevant, simply don't mention it.

                CALIBRATE YOUR LANGUAGE TO THE EVIDENCE
                • Direct evidence in the chunks → state it plainly:
                  "The contract sets the term at three years [2]."
                • Strong implication that you have to read between the lines →
                  hedge softly: "The contract appears to set a three-year
                  term [2] — it's not stated outright but the renewal clause
                  references that window."
                • Weak / fragmentary evidence → be explicit about the gap:
                  "The documents don't say exactly, but section 4.2 [5]
                  mentions a renewal cycle that suggests three years."
                • Nothing supports it → say so and stop. Don't pad. Don't
                  pretend. Don't paraphrase your training data as if it were
                  from the docs.
                Never use absolute language ("definitely", "always", "the only
                possible reading") unless the text itself uses it.

                CONTEXT (%d chunks, %d characters):
                %s
                """.formatted(attachedBlock, relatedBlock, pastBlock,
                context.size(),
                context.stream().mapToInt(c -> c.content().length()).sum(),
                contextBlock);
    }

    // ── Bare-reference detection ──────────────────────────────────────────────

    private static final java.util.regex.Pattern PLURAL_OR_INDEXED_REF =
            java.util.regex.Pattern.compile(
                    "\\b(these|those|them|both|all|first|second|third|earlier|previous|older|other)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern SINGULAR_DEMONSTRATIVE =
            java.util.regex.Pattern.compile(
                    "\\b(this|that|it|its)\\b"
                            + "|\\bthe\\s+(file|doc(ument)?|pdf|attachment|upload|resume|paper|report)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern BARE_ACTION_VERB =
            java.util.regex.Pattern.compile(
                    "^\\s*(summari[sz]e|summary|tl;?dr|explain|describe|analy[sz]e|outline|"
                            + "brief|recap|overview|review|walkthrough|breakdown|key\\s+points|"
                            + "main\\s+points|what's\\s+this(\\s+about)?|continue|go\\s+on)"
                            + "(\\s+(this|that|it|the\\s+\\w+))?\\s*[.!?]?\\s*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * True when the user's message is a bare/demonstrative reference that
     * should anchor on the most-recently-uploaded document (e.g. "explain",
     * "summarize this", "tldr", "what does it say"). False for queries that
     * name something specific or explicitly span multiple docs.
     */
    static boolean isBareDocReference(String query) {
        if (query == null) return false;
        String q = query.trim();
        if (q.isEmpty()) return false;
        // Plural / indexed references want a different doc on purpose.
        if (PLURAL_OR_INDEXED_REF.matcher(q).find()) return false;
        if (BARE_ACTION_VERB.matcher(q).matches()) return true;
        // Very short prompts containing a singular demonstrative.
        if (q.length() <= 60 && SINGULAR_DEMONSTRATIVE.matcher(q).find()) return true;
        return false;
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