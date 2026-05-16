package com.eakp.chat.service;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.annotation.Timed;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Core RAG retrieval pipeline.
 *
 * Flow:
 * 1. Embed the user query (HyDE-expanded when enabled)
 * 2. Run hybrid search (vector ANN + BM25 full-text), optionally per
 *    sub-query for compound questions
 * 3. Fuse results with Reciprocal Rank Fusion (RRF)
 * 4. MMR-diversify to drop near-duplicates
 * 5. Re-rank top candidates with a cross-encoder
 * 6. Return final top-K chunks
 */
@Service
@Slf4j
public class RagPipelineService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final ReRankingService reRankingService;
    private final AttachedDocumentService attachedDocumentService;
    private final ChatClient hydeClient;

    @Value("${app.rag.top-k-retrieve:20}")
    private int topKRetrieve;

    @Value("${app.rag.top-k-rerank:5}")
    private int topKRerank;

    @Value("${app.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    @Value("${app.rag.hyde-enabled:true}")
    private boolean hydeEnabled;

    @Value("${app.rag.subquery-enabled:true}")
    private boolean subQueryEnabled;

    @Value("${app.rag.mmr-similarity-threshold:0.85}")
    private double mmrSimilarityThreshold;

    public RagPipelineService(VectorStore vectorStore,
                              EmbeddingModel embeddingModel,
                              JdbcTemplate jdbcTemplate,
                              ReRankingService reRankingService,
                              AttachedDocumentService attachedDocumentService,
                              @Qualifier("guardChatClient") ChatClient hydeClient) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.reRankingService = reRankingService;
        this.attachedDocumentService = attachedDocumentService;
        this.hydeClient = hydeClient;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Retrieve the most relevant document chunks for a query,
     * scoped strictly to the given workspace (multi-tenant isolation).
     */
    @Timed(value = "rag.retrieve.latency", description = "RAG retrieve pipeline latency")
    public List<RetrievedChunk> retrieve(String query, UUID workspaceId) {
        return retrieve(query, workspaceId, null);
    }

    /**
     * Conversation-aware retrieval. When {@code conversationId} is non-null and
     * the conversation has attached documents (ChatGPT-style uploads), those
     * documents' chunks are <strong>boosted</strong> to the front of the
     * candidate list before re-ranking. Workspace-wide chunks remain eligible
     * as fallback context.
     */
    @Timed(value = "rag.retrieve.latency", description = "RAG retrieve pipeline latency")
    public List<RetrievedChunk> retrieve(String query, UUID workspaceId, UUID conversationId) {
        return retrieve(query, workspaceId, conversationId, null);
    }

    /**
     * Same as {@link #retrieve(String, UUID, UUID)} but with an optional
     * {@code focusedDocId}. When non-null, retrieval is RESTRICTED to that one
     * document — used when the user makes a bare reference ("explain",
     * "summarize this") and we want the answer to be about the file they just
     * uploaded, not whichever earlier doc happens to embed best.
     */
    @Timed(value = "rag.retrieve.latency", description = "RAG retrieve pipeline latency")
    public List<RetrievedChunk> retrieve(String query, UUID workspaceId, UUID conversationId,
                                         UUID focusedDocId) {
        log.info("RAG retrieve: query='{}' workspace={} conversation={} focused={}",
                query, workspaceId, conversationId, focusedDocId);

        // Diagnostic: how many chunks exist at all for this workspace?
        Integer totalChunks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM document_chunks WHERE workspace_id = ?::uuid",
                Integer.class, workspaceId.toString());
        Integer chunksWithEmbedding = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM document_chunks WHERE workspace_id = ?::uuid AND embedding IS NOT NULL",
                Integer.class, workspaceId.toString());
        log.info("Workspace {} has {} chunks ({} with embeddings) in DB",
                workspaceId, totalChunks, chunksWithEmbedding);

        // Resolve attached docs (if any) so we can boost them
        Set<String> attachedDocIds = conversationId == null
                ? Set.of()
                : attachedDocumentService.attachedDocumentIds(conversationId, workspaceId)
                .stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());

        // Focus narrowing: when the caller has pinpointed one document (e.g.
        // user said "explain" right after uploading file X), retrieval should
        // see ONLY that doc's chunks. We still keep the boost path so it stays
        // pinned to the front; everything else is filtered out below.
        final String focusedDocIdStr = focusedDocId == null ? null : focusedDocId.toString();
        if (focusedDocIdStr != null) {
            attachedDocIds = Set.of(focusedDocIdStr);
        }
        if (!attachedDocIds.isEmpty()) {
            log.info("Conversation {} has {} attached document(s) — boosting their chunks",
                    conversationId, attachedDocIds.size());
            // If the user attached a file in the SAME request as their question
            // ("summarize this"), ingestion may still be running. Wait briefly
            // for chunks to appear so we don't falsely answer "no information".
            waitForAttachedChunks(attachedDocIds, workspaceId);
        }

        // 1+2+3: Hybrid hybrid retrieval. For compound queries
        // ("compare A vs B", "differences between X and Y") we decompose
        // into sub-queries, run hybrid search per sub-query, and merge
        // post-RRF — this is how a librarian fetches both books instead of
        // hoping one passage talks about both. For simple queries this is
        // exactly one pass.
        List<String> subQueries = decomposeIfCompound(query);
        if (subQueries.size() > 1) {
            log.info("Compound query decomposed into {} sub-queries: {}",
                    subQueries.size(), subQueries);
        }

        List<ScoredChunk> fused;
        if (subQueries.size() == 1) {
            // Vector search uses HyDE expansion when enabled: we embed a
            // short hypothetical answer instead of the bare question, so
            // abstract / vague queries retrieve passages that *answer* the
            // question rather than passages that *paraphrase* it.
            String vectorQuery = hydeQueryOrFallback(query);
            List<ScoredChunk> vectorResults = vectorSearch(vectorQuery, workspaceId);
            List<ScoredChunk> textResults   = fullTextSearch(query, workspaceId);
            log.info("RAG candidates: vector={} text={} (workspace has {} chunks total)",
                    vectorResults.size(), textResults.size(), totalChunks);
            fused = reciprocalRankFusion(vectorResults, textResults,
                    keywordHeavy(query));
        } else {
            fused = retrieveMultiQuery(subQueries, workspaceId, totalChunks);
        }

        // 3a. Focus narrowing: when a single doc is in focus, drop every
        // chunk that isn't from it BEFORE the boost step. Otherwise the LLM
        // would still see leftover chunks from other attached docs and could
        // anchor the answer there.
        if (focusedDocIdStr != null) {
            fused = fused.stream()
                    .filter(c -> focusedDocIdStr.equals(c.documentId()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if (fused.isEmpty()) {
                // Hybrid search missed — pull directly from the focused doc.
                fused = chunksForDocuments(Set.of(focusedDocIdStr), workspaceId);
            }
        }

        // 3b. Boost: pull attached-document chunks to the front while preserving
        // their relative RRF order; non-attached chunks follow as fallback.
        if (!attachedDocIds.isEmpty()) {
            List<ScoredChunk> attached = new ArrayList<>();
            List<ScoredChunk> remaining = new ArrayList<>();
            for (ScoredChunk c : fused) {
                if (c.documentId() != null && attachedDocIds.contains(c.documentId())) {
                    attached.add(c);
                } else {
                    remaining.add(c);
                }
            }
            // If hybrid search didn't find any chunks from the attached docs at
            // all, query them directly so freshly-uploaded files are always
            // visible to the LLM (vector embeddings can take a moment to land).
            if (attached.isEmpty()) {
                attached = chunksForDocuments(attachedDocIds, workspaceId);
            }
            fused = new ArrayList<>(attached.size() + remaining.size());
            fused.addAll(attached);
            fused.addAll(remaining);
        }

        // 3c. Fallback: if hybrid search found nothing but the workspace HAS
        // chunks, fall back to returning the most recent chunks. This makes
        // resume-style "tell me about this person" queries work even when
        // the embedding similarity is low.
        if (fused.isEmpty() && chunksWithEmbedding != null && chunksWithEmbedding > 0) {
            log.warn(
                    "Hybrid search returned 0 chunks for workspace={} despite {} chunks in DB — falling back to recent chunks",
                    workspaceId, chunksWithEmbedding);
            fused = recentChunksFallback(workspaceId);
        }

        // 3d. MMR diversification — drop chunks whose token-overlap with an
        // already-kept chunk is too high, so the reranker doesn't see five
        // copies of the same paragraph and the LLM gets breadth, not noise.
        // Attached / focused chunks at the head of the list are exempt from
        // dropping so the user's own uploaded material is never filtered
        // out by accident.
        int protectedHead = attachedDocIds.isEmpty() && focusedDocIdStr == null
                ? 0 : Math.min(topKRerank, fused.size());
        fused = mmrDiversify(fused, mmrSimilarityThreshold, protectedHead);

        // 4. Re-rank top candidates
        List<RetrievedChunk> reranked = reRankingService.rerank(
                query,
                fused.stream().limit(topKRetrieve).toList());

        log.info("RAG retrieved {} chunks after re-ranking", reranked.size());
        return reranked.stream().limit(topKRerank).toList();
    }

    // ── L2 HyDE ───────────────────────────────────────────────────────────────

    /**
     * HyDE — Hypothetical Document Embeddings. Ask a cheap LLM to write a
     * 2-3 sentence draft answer to the user's question (knowing nothing
     * about our corpus), then embed THAT for the vector search. The intuition:
     * the hypothetical answer is in "answer space", so its embedding lands
     * closer to the passages that actually answer the question than the
     * question's own embedding does.
     *
     * <p>Disabled via {@code app.rag.hyde-enabled=false}. Short queries
     * (< 3 words) and queries that look like keyword lookups skip HyDE
     * — for "Q1 revenue 2024" the bare query is already in answer-space.
     */
    String hydeQueryOrFallback(String query) {
        if (!hydeEnabled) return query;
        if (query == null) return query;
        String trimmed = query.trim();
        if (trimmed.split("\\s+").length < 3) return query;
        // Keyword-heavy queries don't benefit from HyDE — the keywords ARE
        // the search terms, expanding them dilutes precision.
        if (keywordHeavy(trimmed)) return query;
        try {
            String prompt = """
                    Write 2-3 plain sentences that COULD plausibly appear inside
                    a document and would directly answer the user's question.
                    No preamble, no caveats, no "I don't know" — just the kind
                    of factual prose the source material would contain.

                    Question: %s
                    """.formatted(trimmed);
            String hypothetical = hydeClient.prompt().user(prompt).call().content();
            if (hypothetical == null || hypothetical.isBlank()) return query;
            // Prepend the original so keywords from the question still
            // influence the embedding — pure-HyDE risks drifting too far.
            return trimmed + "\n" + hypothetical.trim();
        } catch (Exception e) {
            log.debug("HyDE generation failed ({}), using raw query", e.getMessage());
            return query;
        }
    }

    // ── L3 MMR diversification ────────────────────────────────────────────────

    /**
     * Greedy MMR — walk the RRF-sorted list and drop any chunk whose token
     * Jaccard with an already-kept chunk exceeds {@code threshold}. The
     * first {@code protectedHead} chunks are kept verbatim (boost path
     * already guarantees they belong) and ALSO seed the kept set so later
     * near-duplicates get dropped against them.
     */
    static List<ScoredChunk> mmrDiversify(List<ScoredChunk> sorted,
                                          double threshold,
                                          int protectedHead) {
        if (sorted.isEmpty()) return sorted;
        List<ScoredChunk> kept = new ArrayList<>(sorted.size());
        List<Set<String>> keptTokens = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ScoredChunk c = sorted.get(i);
            Set<String> tokens = tokenSet(c.content());
            if (i < protectedHead) {
                kept.add(c);
                keptTokens.add(tokens);
                continue;
            }
            boolean dup = false;
            for (Set<String> prev : keptTokens) {
                if (jaccard(tokens, prev) >= threshold) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                kept.add(c);
                keptTokens.add(tokens);
            }
        }
        return kept;
    }

    private static final java.util.regex.Pattern TOKEN_SPLIT =
            java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");

    private static Set<String> tokenSet(String s) {
        if (s == null || s.isEmpty()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : TOKEN_SPLIT.split(s.toLowerCase())) {
            if (t.length() > 2) out.add(t);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        Set<String> smaller = a.size() < b.size() ? a : b;
        Set<String> larger  = smaller == a ? b : a;
        for (String t : smaller) if (larger.contains(t)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    // ── L4 Sub-query decomposition ────────────────────────────────────────────

    private static final java.util.regex.Pattern COMPOUND_HINT =
            java.util.regex.Pattern.compile(
                    "\\b(compare|comparison|contrast|versus|vs\\.?|differences? between|"
                            + "how does .+ compare|what.+(differ|vary))\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Return either {@code [query]} for ordinary questions, or a small list
     * of atomic sub-queries when the question is comparative / compound. A
     * librarian fetches both books when asked to compare them.
     */
    List<String> decomposeIfCompound(String query) {
        List<String> single = List.of(query);
        if (!subQueryEnabled) return single;
        if (query == null || query.length() < 12) return single;
        if (!COMPOUND_HINT.matcher(query).find()) return single;
        try {
            String prompt = """
                    Split the user's comparative question into 2 or 3 atomic
                    sub-questions, each asking about ONE side of the comparison.
                    Respond ONLY with a JSON array of strings. No prose, no
                    keys, just the array.

                    Example:
                      Input: "Compare React and Vue for state management"
                      Output: ["How does React handle state management?",
                              "How does Vue handle state management?"]

                    Question: %s
                    """.formatted(query);
            String raw = hydeClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) return single;
            int lb = raw.indexOf('['), rb = raw.lastIndexOf(']');
            if (lb < 0 || rb < lb) return single;
            String json = raw.substring(lb, rb + 1);
            List<String> parts = new ArrayList<>();
            // Tolerant parsing: split on "," that are between quotes.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"").matcher(json);
            while (m.find()) parts.add(m.group(1).trim());
            if (parts.isEmpty()) return single;
            // Always include the original query so we never lose recall if
            // decomposition went off-track.
            List<String> out = new ArrayList<>(parts.size() + 1);
            out.add(query);
            for (String p : parts) {
                if (!p.isBlank() && !p.equalsIgnoreCase(query)) out.add(p);
            }
            // Cap at 4 sub-queries to bound latency.
            return out.size() > 4 ? out.subList(0, 4) : out;
        } catch (Exception e) {
            log.debug("Sub-query decomposition failed ({}), using original",
                    e.getMessage());
            return single;
        }
    }

    /**
     * Run hybrid retrieval once per sub-query and merge the post-RRF lists
     * by best (lowest) per-id rank. Dedupes by chunk id — a chunk that ranks
     * top-3 for sub-query 1 and top-5 for sub-query 2 keeps its top-3 rank.
     */
    private List<ScoredChunk> retrieveMultiQuery(List<String> queries,
                                                 UUID workspaceId,
                                                 Integer totalChunks) {
        Map<String, ScoredChunk> bestById = new LinkedHashMap<>();
        Map<String, Integer> bestRankById = new HashMap<>();
        for (String q : queries) {
            String vectorQuery = hydeQueryOrFallback(q);
            List<ScoredChunk> vec = vectorSearch(vectorQuery, workspaceId);
            List<ScoredChunk> txt = fullTextSearch(q, workspaceId);
            List<ScoredChunk> fused = reciprocalRankFusion(vec, txt, keywordHeavy(q));
            for (int i = 0; i < fused.size(); i++) {
                ScoredChunk c = fused.get(i);
                Integer prev = bestRankById.get(c.id());
                if (prev == null || i < prev) {
                    bestById.put(c.id(), c);
                    bestRankById.put(c.id(), i);
                }
            }
        }
        log.info("Multi-query retrieval: {} sub-queries → {} unique candidates",
                queries.size(), bestById.size());
        // Sort by the best rank achieved across any sub-query.
        return bestById.values().stream()
                .sorted(Comparator.comparingInt(c -> bestRankById.get(c.id())))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Poll up to ~12s for at least one chunk to appear for any of the attached
     * docs. Covers the race where the user attaches a file and immediately
     * asks about it before async ingestion has written rows.
     */
    private void waitForAttachedChunks(Set<String> docIds, UUID workspaceId) {
        if (docIds.isEmpty()) return;
        String inList = String.join(",",
                docIds.stream().map(id -> "'" + id.replace("'", "") + "'").toList());
        String sql = ("SELECT COUNT(*)::int FROM document_chunks "
                + "WHERE workspace_id = ?::uuid AND document_id IN (" + inList + ")");
        long deadline = System.currentTimeMillis() + 12_000L;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                Integer n = jdbcTemplate.queryForObject(sql, Integer.class, workspaceId.toString());
                if (n != null && n > 0) {
                    if (attempt > 0) {
                        log.info("Waited {} attempt(s) for attached-doc chunks — found {} chunk(s)",
                                attempt, n);
                    }
                    return;
                }
            } catch (Exception ignored) {}
            attempt++;
            try { Thread.sleep(1_500L); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("Attached docs still have no chunks after wait — ingestion may not be done yet");
    }

    /**
     * Direct fetch of every chunk for a given set of document IDs
     * (workspace-scoped). Uses a true bound parameter for the IN-list so
     * we never build SQL by concatenating values, even when the values are
     * "just" UUIDs — defence in depth against latent injection if a future
     * caller ever passes user input.
     */
    private List<ScoredChunk> chunksForDocuments(Set<String> docIds, UUID workspaceId) {
        if (docIds.isEmpty())
            return List.of();
        String sql = """
                SELECT id::text, content,
                       metadata->>'source'      AS source,
                       metadata->>'document_id' AS document_id
                FROM document_chunks
                WHERE workspace_id = ?::uuid
                  AND document_id::text = ANY(?)
                ORDER BY chunk_index ASC
                LIMIT ?
                """;
        try {
            String[] idArray = docIds.toArray(new String[0]);
            List<Map<String, Object>> rows = jdbcTemplate.query(
                    con -> {
                        var ps = con.prepareStatement(sql);
                        ps.setString(1, workspaceId.toString());
                        ps.setArray(2, con.createArrayOf("text", idArray));
                        ps.setInt(3, topKRetrieve);
                        return ps;
                    },
                    (rs, n) -> {
                        Map<String, Object> r = new HashMap<>();
                        r.put("id", rs.getString("id"));
                        r.put("content", rs.getString("content"));
                        r.put("source", rs.getString("source"));
                        r.put("document_id", rs.getString("document_id"));
                        return r;
                    });
            List<ScoredChunk> out = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> r = rows.get(i);
                out.add(new ScoredChunk(
                        String.valueOf(r.get("id")),
                        String.valueOf(r.get("content")),
                        String.valueOf(r.get("source")),
                        String.valueOf(r.get("document_id")),
                        i + 1, 0));
            }
            return out;
        } catch (Exception e) {
            log.warn("Direct chunk fetch for attached docs failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Last-resort fallback: return the most recent chunks for the workspace. */
    private List<ScoredChunk> recentChunksFallback(UUID workspaceId) {
        String sql = """
                SELECT id::text, content,
                       metadata->>'source'      AS source,
                       metadata->>'document_id' AS document_id
                FROM document_chunks
                WHERE workspace_id = ?::uuid
                ORDER BY created_at DESC
                LIMIT ?
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql, workspaceId.toString(), topKRetrieve);
        List<ScoredChunk> results = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            results.add(new ScoredChunk(
                    String.valueOf(row.get("id")),
                    String.valueOf(row.get("content")),
                    String.valueOf(row.get("source")),
                    String.valueOf(row.get("document_id")),
                    i + 1, 0));
        }
        return results;
    }

    // ── Vector Search ─────────────────────────────────────────────────────────

    private List<ScoredChunk> vectorSearch(String query, UUID workspaceId) {
        // Spring AI's VectorStore supports metadata filtering for tenant isolation
        var filterBuilder = new FilterExpressionBuilder();
        var filter = filterBuilder.eq("workspace_id", workspaceId.toString()).build();

        var request = SearchRequest.builder()
                .query(query)
                .topK(topKRetrieve)
                .filterExpression(filter)
                .similarityThreshold(similarityThreshold)
                .build();

        List<Document> docs;
        try {
            docs = vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.error("Vector search failed for workspace={}: {}", workspaceId, e.getMessage(), e);
            return List.of();
        }

        List<ScoredChunk> results = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            results.add(new ScoredChunk(
                    doc.getId(),
                    doc.getFormattedContent(),
                    extractMeta(doc, "source"),
                    extractMeta(doc, "document_id"),
                    i + 1, // rank (1-based)
                    0 // text rank (not applicable here)
            ));
        }
        log.debug("Vector search returned {} results", results.size());
        return results;
    }

    // ── Full-Text (BM25) Search ───────────────────────────────────────────────

    private List<ScoredChunk> fullTextSearch(String query, UUID workspaceId) {
        // websearch_to_tsquery understands quotes, OR, and `-` exclusion, so
        // users can ask `"exact phrase"` or `foo OR bar` and have it work.
        // We OR the content tsvector with one over the filename so proper
        // nouns / SKUs / "the spec.pdf" land on the right doc even when the
        // body doesn't repeat them.
        String sql = """
                SELECT
                    id::text,
                    content,
                    metadata->>'source'      AS source,
                    metadata->>'document_id' AS document_id,
                    ts_rank(
                        to_tsvector('english', content) ||
                        to_tsvector('english', coalesce(metadata->>'source','')),
                        websearch_to_tsquery('english', ?)
                    ) AS score
                FROM document_chunks
                WHERE workspace_id = ?::uuid
                  AND (
                        to_tsvector('english', content)
                            @@ websearch_to_tsquery('english', ?)
                     OR to_tsvector('english', coalesce(metadata->>'source',''))
                            @@ websearch_to_tsquery('english', ?)
                  )
                ORDER BY score DESC
                LIMIT ?
                """;

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(
                    sql, query, workspaceId.toString(), query, query, topKRetrieve);
        } catch (Exception e) {
            // websearch_to_tsquery is strict about some characters; fall back
            // to plainto_tsquery on parse error so we never lose recall.
            log.debug("websearch_to_tsquery failed ({}), falling back to plainto", e.getMessage());
            String fallbackSql = """
                    SELECT id::text, content,
                           metadata->>'source'      AS source,
                           metadata->>'document_id' AS document_id,
                           ts_rank(to_tsvector('english', content),
                                   plainto_tsquery('english', ?)) AS score
                    FROM document_chunks
                    WHERE workspace_id = ?::uuid
                      AND to_tsvector('english', content)
                          @@ plainto_tsquery('english', ?)
                    ORDER BY score DESC
                    LIMIT ?
                    """;
            rows = jdbcTemplate.queryForList(
                    fallbackSql, query, workspaceId.toString(), query, topKRetrieve);
        }

        List<ScoredChunk> results = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            results.add(new ScoredChunk(
                    (String) row.get("id"),
                    (String) row.get("content"),
                    (String) row.get("source"),
                    (String) row.get("document_id"),
                    0, // vector rank (not applicable)
                    i + 1 // text rank (1-based)
            ));
        }
        log.debug("Full-text search returned {} results", results.size());
        return results;
    }

    // ── Reciprocal Rank Fusion ────────────────────────────────────────────────

    /**
     * RRF score = Σ 1 / (k + rank_i). Standard k = 60. When
     * {@code keywordHeavy} is true, the vector results use a larger k (90)
     * so they contribute less than text results (k = 30) — the right bias
     * for queries dominated by distinctive tokens / IDs / proper nouns.
     */
    private List<ScoredChunk> reciprocalRankFusion(
            List<ScoredChunk> vectorResults,
            List<ScoredChunk> textResults,
            boolean keywordHeavy) {

        final int kVector = keywordHeavy ? 90 : 60;
        final int kText   = keywordHeavy ? 30 : 60;
        Map<String, double[]> scoreMap = new HashMap<>(); // id -> [rrfScore]
        Map<String, ScoredChunk> chunkMap = new HashMap<>();

        // Add vector ranks
        for (ScoredChunk chunk : vectorResults) {
            double rrf = 1.0 / (kVector + chunk.vectorRank());
            scoreMap.computeIfAbsent(chunk.id(),
                    id -> new double[] { 0.0 })[0] += rrf;
            chunkMap.putIfAbsent(chunk.id(), chunk);
        }

        // Add text ranks
        for (ScoredChunk chunk : textResults) {
            double rrf = 1.0 / (kText + chunk.textRank());
            scoreMap.computeIfAbsent(chunk.id(),
                    id -> new double[] { 0.0 })[0] += rrf;
            chunkMap.putIfAbsent(chunk.id(), chunk);
        }

        // Sort by fused RRF score descending
        return scoreMap.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> chunkMap.get(e.getKey()))
                .toList();
    }

    // Distinctive-token detection: an uppercase noun ≥3 chars, a digit run
    // ≥3 long, a quoted phrase, or an alphanumeric "ID" token. When any of
    // these are present the query is biased toward text-search results
    // because exact-match recall matters more than paraphrase recall.
    private static final java.util.regex.Pattern KEYWORD_HEAVY = java.util.regex.Pattern.compile(
            "\"[^\"]+\""                    // quoted phrase
                    + "|\\b[A-Z][A-Za-z0-9]{2,}\\b"  // proper noun / camel-case
                    + "|\\b\\d{3,}\\b"        // multi-digit number
                    + "|\\b[A-Z0-9]{2,}[-_][A-Z0-9]+\\b" // SKU/ID-like (FOO-123)
    );

    static boolean keywordHeavy(String query) {
        if (query == null || query.length() < 3) return false;
        return KEYWORD_HEAVY.matcher(query).find();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractMeta(Document doc, String key) {
        Object val = doc.getMetadata().get(key);
        return val != null ? val.toString() : "unknown";
    }

    // ── Value Objects ─────────────────────────────────────────────────────────

    public record ScoredChunk(
            String id,
            String content,
            String source,
            String documentId,
            int vectorRank,
            int textRank) {
    }

    public record RetrievedChunk(
            String id,
            String content,
            String source,
            String documentId,
            double relevanceScore) {
        /** Format for injection into the LLM prompt. */
        public String toPromptString() {
            return "[Source: %s]\n%s".formatted(source, content);
        }
    }
}