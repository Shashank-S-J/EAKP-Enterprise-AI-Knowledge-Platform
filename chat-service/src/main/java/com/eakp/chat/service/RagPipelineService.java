package com.eakp.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.annotation.Timed;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Core RAG retrieval pipeline.
 *
 * Flow:
 * 1. Embed the user query
 * 2. Run hybrid search (vector ANN + BM25 full-text)
 * 3. Fuse results with Reciprocal Rank Fusion (RRF)
 * 4. Re-rank top candidates with a cross-encoder
 * 5. Return final top-K chunks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagPipelineService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final ReRankingService reRankingService;
    private final AttachedDocumentService attachedDocumentService;

    @Value("${app.rag.top-k-retrieve:20}")
    private int topKRetrieve;

    @Value("${app.rag.top-k-rerank:5}")
    private int topKRerank;

    @Value("${app.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

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
        log.info("RAG retrieve: query='{}' workspace={} conversation={}",
                query, workspaceId, conversationId);

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
        if (!attachedDocIds.isEmpty()) {
            log.info("Conversation {} has {} attached document(s) — boosting their chunks",
                    conversationId, attachedDocIds.size());
            // If the user attached a file in the SAME request as their question
            // ("summarize this"), ingestion may still be running. Wait briefly
            // for chunks to appear so we don't falsely answer "no information".
            waitForAttachedChunks(attachedDocIds, workspaceId);
        }

        // 1. Vector search (semantic)
        List<ScoredChunk> vectorResults = vectorSearch(query, workspaceId);

        // 2. Full-text search (BM25 / keyword)
        List<ScoredChunk> textResults = fullTextSearch(query, workspaceId);

        log.info("RAG candidates: vector={} text={} (workspace has {} chunks total)",
                vectorResults.size(), textResults.size(), totalChunks);

        // 3. Reciprocal Rank Fusion
        List<ScoredChunk> fused = reciprocalRankFusion(vectorResults, textResults);

        // 3a. Boost: pull attached-document chunks to the front while preserving
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

        // 3b. Fallback: if hybrid search found nothing but the workspace HAS
        // chunks, fall back to returning the most recent chunks. This makes
        // resume-style "tell me about this person" queries work even when
        // the embedding similarity is low.
        if (fused.isEmpty() && chunksWithEmbedding != null && chunksWithEmbedding > 0) {
            log.warn(
                    "Hybrid search returned 0 chunks for workspace={} despite {} chunks in DB — falling back to recent chunks",
                    workspaceId, chunksWithEmbedding);
            fused = recentChunksFallback(workspaceId);
        }

        // 4. Re-rank top candidates
        List<RetrievedChunk> reranked = reRankingService.rerank(
                query,
                fused.stream().limit(topKRetrieve).toList());

        log.info("RAG retrieved {} chunks after re-ranking", reranked.size());
        return reranked.stream().limit(topKRerank).toList();
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
     * (workspace-scoped).
     */
    private List<ScoredChunk> chunksForDocuments(Set<String> docIds, UUID workspaceId) {
        if (docIds.isEmpty())
            return List.of();
        String inList = String.join(",",
                docIds.stream().map(id -> "'" + id.replace("'", "") + "'").toList());
        String sql = """
                SELECT id::text, content,
                       metadata->>'source'      AS source,
                       metadata->>'document_id' AS document_id
                FROM document_chunks
                WHERE workspace_id = ?::uuid
                  AND document_id IN (%s)
                ORDER BY chunk_index ASC
                LIMIT ?
                """.formatted(inList);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql, workspaceId.toString(), topKRetrieve);
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
        String sql = """
                SELECT
                    id::text,
                    content,
                    metadata->>'source'      AS source,
                    metadata->>'document_id' AS document_id,
                    ts_rank(
                        to_tsvector('english', content),
                        plainto_tsquery('english', ?)
                    ) AS score
                FROM document_chunks
                WHERE workspace_id = ?::uuid
                  AND to_tsvector('english', content)
                      @@ plainto_tsquery('english', ?)
                ORDER BY score DESC
                LIMIT ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql, query, workspaceId.toString(), query, topKRetrieve);

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
     * RRF score = Σ 1 / (k + rank_i) where k=60 (standard constant).
     * Merges vector and text results into a single ranked list.
     */
    private List<ScoredChunk> reciprocalRankFusion(
            List<ScoredChunk> vectorResults,
            List<ScoredChunk> textResults) {

        final int K = 60;
        Map<String, double[]> scoreMap = new HashMap<>(); // id -> [rrfScore, idx]
        Map<String, ScoredChunk> chunkMap = new HashMap<>();

        // Add vector ranks
        for (ScoredChunk chunk : vectorResults) {
            double rrf = 1.0 / (K + chunk.vectorRank());
            scoreMap.computeIfAbsent(chunk.id(),
                    id -> new double[] { 0.0 })[0] += rrf;
            chunkMap.putIfAbsent(chunk.id(), chunk);
        }

        // Add text ranks
        for (ScoredChunk chunk : textResults) {
            double rrf = 1.0 / (K + chunk.textRank());
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