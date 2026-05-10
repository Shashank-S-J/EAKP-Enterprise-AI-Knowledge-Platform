package com.eakp.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.annotation.Timed;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Semantic cache backed by pgvector's semantic_cache table.
 *
 * Instead of Redis KEYS scan (O(N), blocks Redis), we use the HNSW index
 * on the semantic_cache table for O(log N) cosine similarity search.
 *
 * Cache entries auto-expire via the expires_at column.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheService {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate   jdbcTemplate;

    @Value("${app.rag.semantic-cache-threshold:0.92}")
    private double threshold;

    @Value("${app.rag.cache-ttl-hours:24}")
    private int cacheTtlHours;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Look for a semantically similar cached answer.
     * Uses pgvector HNSW index for fast cosine similarity search.
     * Returns empty if nothing matches above the threshold.
     */
    @Timed(value = "rag.cache.lookup.latency", description = "Semantic cache lookup latency")
    public Optional<CachedAnswer> findSimilar(String query, UUID workspaceId) {
        float[] queryEmbedding = embed(query);
        String vectorLiteral = toVectorLiteral(queryEmbedding);

        String sql = """
            SELECT id, query, answer, hit_count,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM semantic_cache
            WHERE workspace_id = ?::uuid
              AND expires_at > NOW()
            ORDER BY embedding <=> ?::vector
            LIMIT 1
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql, vectorLiteral, workspaceId.toString(), vectorLiteral);

        if (rows.isEmpty()) {
            log.debug("Cache MISS workspace={} (no entries)", workspaceId);
            return Optional.empty();
        }

        Map<String, Object> row = rows.get(0);
        double similarity = ((Number) row.get("similarity")).doubleValue();

        if (similarity >= threshold) {
            log.info("Cache HIT  workspace={} score={}", workspaceId, similarity);
            incrementHitCount((UUID) row.get("id"));
            return Optional.of(new CachedAnswer(
                    (String) row.get("answer"),
                    List.of(), // sources not stored in table; cosmetic
                    similarity));
        }

        log.debug("Cache MISS workspace={} bestScore={}", workspaceId, similarity);
        return Optional.empty();
    }

    /**
     * Store a question-answer pair in the semantic cache.
     */
    public void store(String query, UUID workspaceId,
                      String answer, List<String> sources) {
        try {
            float[] embedding = embed(query);
            String vectorLiteral = toVectorLiteral(embedding);

            String sql = """
                INSERT INTO semantic_cache (workspace_id, query, answer, embedding, expires_at)
                VALUES (?::uuid, ?, ?, ?::vector, NOW() + MAKE_INTERVAL(hours => ?))
                """;

            jdbcTemplate.update(sql,
                    workspaceId.toString(), query, answer, vectorLiteral, cacheTtlHours);
            log.debug("Cached answer for workspace={}", workspaceId);
        } catch (Exception e) {
            log.warn("Failed to cache answer: {}", e.getMessage());
        }
    }

    /**
     * Evict all cache entries for a workspace.
     * Call this when new documents are ingested to prevent stale answers.
     */
    public void evictWorkspace(UUID workspaceId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM semantic_cache WHERE workspace_id = ?::uuid",
                workspaceId.toString());
        if (deleted > 0) {
            log.info("Evicted {} cache entries for workspace={}", deleted, workspaceId);
        }
    }

    /**
     * Cleanup of expired entries.
     * Called by CachePurgeScheduler on a configurable interval.
     */
    public int purgeExpired() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM semantic_cache WHERE expires_at < NOW()");
        if (deleted > 0) {
            log.info("Purged {} expired cache entries", deleted);
        }
        return deleted;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    private String toVectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private void incrementHitCount(UUID cacheEntryId) {
        try {
            jdbcTemplate.update("""
                UPDATE semantic_cache
                SET hit_count = hit_count + 1, last_hit_at = NOW()
                WHERE id = ?::uuid
                """, cacheEntryId.toString());
        } catch (Exception e) {
            log.debug("Could not increment hit count: {}", e.getMessage());
        }
    }

    // ── Value Objects ─────────────────────────────────────────────────────────

    public record CachedAnswer(
        String       answer,
        List<String> sources,
        double       similarityScore
    ) {}
}
