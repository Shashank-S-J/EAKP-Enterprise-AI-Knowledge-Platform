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
 *   1. Embed the user query
 *   2. Run hybrid search (vector ANN + BM25 full-text)
 *   3. Fuse results with Reciprocal Rank Fusion (RRF)
 *   4. Re-rank top candidates with a cross-encoder
 *   5. Return final top-K chunks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagPipelineService {

    private final VectorStore     vectorStore;
    private final EmbeddingModel  embeddingModel;
    private final JdbcTemplate    jdbcTemplate;
    private final ReRankingService reRankingService;

    @Value("${app.rag.top-k-retrieve:20}")
    private int topKRetrieve;

    @Value("${app.rag.top-k-rerank:5}")
    private int topKRerank;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Retrieve the most relevant document chunks for a query,
     * scoped strictly to the given workspace (multi-tenant isolation).
     */
    @Timed(value = "rag.retrieve.latency", description = "RAG retrieve pipeline latency")
    public List<RetrievedChunk> retrieve(String query, UUID workspaceId) {
        log.debug("RAG retrieve: query='{}' workspace={}", query, workspaceId);

        // 1. Vector search (semantic)
        List<ScoredChunk> vectorResults = vectorSearch(query, workspaceId);

        // 2. Full-text search (BM25 / keyword)
        List<ScoredChunk> textResults = fullTextSearch(query, workspaceId);

        // 3. Reciprocal Rank Fusion
        List<ScoredChunk> fused = reciprocalRankFusion(vectorResults, textResults);

        // 4. Re-rank top candidates
        List<RetrievedChunk> reranked = reRankingService.rerank(
                query,
                fused.stream().limit(topKRetrieve).toList());

        log.debug("RAG retrieved {} chunks after re-ranking", reranked.size());
        return reranked.stream().limit(topKRerank).toList();
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
                .similarityThreshold(0.6)   // discard weak matches (tuned for precision)
                .build();

        List<Document> docs = vectorStore.similaritySearch(request);

        List<ScoredChunk> results = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            results.add(new ScoredChunk(
                    doc.getId(),
                    doc.getFormattedContent(),
                    extractMeta(doc, "source"),
                    extractMeta(doc, "document_id"),
                    i + 1,   // rank (1-based)
                    0         // text rank (not applicable here)
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
                    0,          // vector rank (not applicable)
                    i + 1       // text rank (1-based)
            ));
        }
        log.debug("Full-text search returned {} results", results.size());
        return results;
    }

    // ── Reciprocal Rank Fusion ────────────────────────────────────────────────

    /**
     * RRF score = Σ 1 / (k + rank_i)   where k=60 (standard constant).
     * Merges vector and text results into a single ranked list.
     */
    private List<ScoredChunk> reciprocalRankFusion(
            List<ScoredChunk> vectorResults,
            List<ScoredChunk> textResults) {

        final int K = 60;
        Map<String, double[]> scoreMap = new HashMap<>();  // id -> [rrfScore, idx]
        Map<String, ScoredChunk> chunkMap = new HashMap<>();

        // Add vector ranks
        for (ScoredChunk chunk : vectorResults) {
            double rrf = 1.0 / (K + chunk.vectorRank());
            scoreMap.computeIfAbsent(chunk.id(),
                    id -> new double[]{0.0})[0] += rrf;
            chunkMap.putIfAbsent(chunk.id(), chunk);
        }

        // Add text ranks
        for (ScoredChunk chunk : textResults) {
            double rrf = 1.0 / (K + chunk.textRank());
            scoreMap.computeIfAbsent(chunk.id(),
                    id -> new double[]{0.0})[0] += rrf;
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
        int    vectorRank,
        int    textRank
    ) {}

    public record RetrievedChunk(
        String id,
        String content,
        String source,
        String documentId,
        double relevanceScore
    ) {
        /** Format for injection into the LLM prompt. */
        public String toPromptString() {
            return "[Source: %s]\n%s".formatted(source, content);
        }
    }
}
