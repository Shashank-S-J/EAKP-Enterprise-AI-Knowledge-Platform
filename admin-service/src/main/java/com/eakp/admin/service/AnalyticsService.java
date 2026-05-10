package com.eakp.admin.service;

import com.eakp.admin.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregates analytics data from PostgreSQL and Redis.
 *
 * These are the metrics shown on the admin dashboard:
 *   - Workspace overview (documents, chunks, conversations, messages)
 *   - RAG quality (avg faithfulness, grounded vs ungrounded ratio)
 *   - Usage (queries per day, cache hit rate, top questions)
 *   - Document stats (per-document chunk counts, status breakdown)
 *   - System health (pending/failed documents, DLQ depth)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final JdbcTemplate        jdbc;
    private final StringRedisTemplate redis;

    // ── Workspace Overview ────────────────────────────────────────────────────

    public WorkspaceStatsDto getWorkspaceStats(UUID workspaceId) {
        long documents = queryLong(
            "SELECT COUNT(*) FROM documents WHERE workspace_id = ?::uuid",
            workspaceId.toString());

        long readyDocs = queryLong(
            "SELECT COUNT(*) FROM documents WHERE workspace_id = ?::uuid AND status = 'READY'",
            workspaceId.toString());

        long chunks = queryLong(
            "SELECT COUNT(*) FROM document_chunks WHERE workspace_id = ?::uuid",
            workspaceId.toString());

        long conversations = queryLong(
            "SELECT COUNT(*) FROM conversations WHERE workspace_id = ?::uuid",
            workspaceId.toString());

        long messages = queryLong(
            """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid
            """, workspaceId.toString());

        long cacheEntries = countRedisKeys("sem-cache:" + workspaceId + ":*");

        return new WorkspaceStatsDto(
            workspaceId.toString(),
            documents, readyDocs, chunks,
            conversations, messages, cacheEntries
        );
    }

    // ── RAG Quality Metrics ───────────────────────────────────────────────────

    public RagQualityDto getRagQuality(UUID workspaceId) {
        // Average faithfulness score across all ASSISTANT messages
        Double avgFaithfulness = jdbc.queryForObject(
            """
            SELECT AVG(m.faithfulness)
            FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid
              AND m.role = 'ASSISTANT'
              AND m.faithfulness IS NOT NULL
            """,
            Double.class, workspaceId.toString());

        // Messages with faithfulness < 0.5 (potential hallucinations)
        long lowFaithfulness = queryLong(
            """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid
              AND m.role = 'ASSISTANT'
              AND m.faithfulness < 0.5
            """, workspaceId.toString());

        long totalAssistant = queryLong(
            """
            SELECT COUNT(*) FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid AND m.role = 'ASSISTANT'
            """, workspaceId.toString());

        // Faithfulness distribution buckets
        List<Map<String, Object>> distribution = jdbc.queryForList(
            """
            SELECT
                CASE
                    WHEN faithfulness >= 0.9 THEN '0.9-1.0 (Excellent)'
                    WHEN faithfulness >= 0.7 THEN '0.7-0.9 (Good)'
                    WHEN faithfulness >= 0.5 THEN '0.5-0.7 (Fair)'
                    ELSE '0.0-0.5 (Poor)'
                END AS bucket,
                COUNT(*) AS count
            FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid
              AND m.role = 'ASSISTANT'
              AND m.faithfulness IS NOT NULL
            GROUP BY bucket ORDER BY bucket DESC
            """, workspaceId.toString());

        double hallucinationRate = totalAssistant > 0
                ? (double) lowFaithfulness / totalAssistant : 0.0;

        return new RagQualityDto(
            avgFaithfulness != null ? avgFaithfulness : 0.0,
            hallucinationRate,
            lowFaithfulness,
            totalAssistant,
            distribution
        );
    }

    // ── Usage Analytics ───────────────────────────────────────────────────────

    public UsageStatsDto getUsageStats(UUID workspaceId, int days) {
        // Queries per day (last N days)
        String queriesPerDaySql = String.format("""
            SELECT DATE(m.created_at) AS day, COUNT(*) AS count
            FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid
              AND m.role = 'USER'
              AND m.created_at >= NOW() - INTERVAL '%d days'
            GROUP BY day ORDER BY day
            """, days);
        List<Map<String, Object>> queriesPerDay = jdbc.queryForList(
            queriesPerDaySql, workspaceId.toString());

        // Top 10 most asked questions (simple approach: by frequency of similar starts)
        List<Map<String, Object>> topQuestions = jdbc.queryForList(
            """
            SELECT content, COUNT(*) AS frequency
            FROM messages m
            JOIN conversations c ON m.conversation_id = c.id
            WHERE c.workspace_id = ?::uuid AND m.role = 'USER'
            GROUP BY content
            ORDER BY frequency DESC
            LIMIT 10
            """, workspaceId.toString());

        // Active users (distinct user conversations last 7 days)
        long activeUsers = queryLong(
            """
            SELECT COUNT(DISTINCT user_id)
            FROM conversations
            WHERE workspace_id = ?::uuid
              AND updated_at >= NOW() - INTERVAL '7 days'
            """, workspaceId.toString());

        // Cache hit rate from Redis key counts
        long cacheEntries = countRedisKeys("sem-cache:" + workspaceId + ":*");

        return new UsageStatsDto(
            queriesPerDay, topQuestions,
            activeUsers, cacheEntries, days
        );
    }

    // ── Document Analytics ────────────────────────────────────────────────────

    public DocumentStatsDto getDocumentStats(UUID workspaceId) {
        // Status breakdown
        List<Map<String, Object>> statusBreakdown = jdbc.queryForList(
            """
            SELECT status, COUNT(*) AS count
            FROM documents
            WHERE workspace_id = ?::uuid
            GROUP BY status
            """, workspaceId.toString());

        // Top documents by chunk count (most chunked = most content)
        List<Map<String, Object>> topDocuments = jdbc.queryForList(
            """
            SELECT filename, chunk_count, file_size, status,
                   created_at, updated_at
            FROM documents
            WHERE workspace_id = ?::uuid AND status = 'READY'
            ORDER BY chunk_count DESC NULLS LAST
            LIMIT 20
            """, workspaceId.toString());

        // Failed documents needing attention
        List<Map<String, Object>> failedDocs = jdbc.queryForList(
            """
            SELECT id, filename, error_msg, created_at
            FROM documents
            WHERE workspace_id = ?::uuid AND status = 'FAILED'
            ORDER BY created_at DESC
            """, workspaceId.toString());

        // Average processing time (would need timing column; approximate)
        double avgChunksPerDoc = jdbc.queryForObject(
            """
            SELECT COALESCE(AVG(chunk_count), 0)
            FROM documents
            WHERE workspace_id = ?::uuid AND status = 'READY'
            """, Double.class, workspaceId.toString());

        return new DocumentStatsDto(
            statusBreakdown, topDocuments,
            failedDocs, avgChunksPerDoc
        );
    }

    // ── System Health ─────────────────────────────────────────────────────────

    public SystemHealthDto getSystemHealth() {
        long pendingDocs = queryLong(
            "SELECT COUNT(*) FROM documents WHERE status = 'PENDING'");
        long processingDocs = queryLong(
            "SELECT COUNT(*) FROM documents WHERE status = 'PROCESSING'");
        long failedDocs = queryLong(
            "SELECT COUNT(*) FROM documents WHERE status = 'FAILED'");
        long totalChunks = queryLong(
            "SELECT COUNT(*) FROM document_chunks");
        long totalUsers = queryLong(
            "SELECT COUNT(*) FROM users WHERE active = true");
        long totalWorkspaces = queryLong(
            "SELECT COUNT(*) FROM workspaces");
        long totalCacheEntries = countRedisKeys("sem-cache:*");

        return new SystemHealthDto(
            pendingDocs, processingDocs, failedDocs,
            totalChunks, totalUsers, totalWorkspaces, totalCacheEntries
        );
    }

    // ── Workspace Management ──────────────────────────────────────────────────

    public List<Map<String, Object>> listWorkspaces() {
        return jdbc.queryForList(
            """
            SELECT w.id, w.name, w.slug, w.created_at,
                   COUNT(DISTINCT u.id)  AS user_count,
                   COUNT(DISTINCT d.id)  AS document_count,
                   COUNT(DISTINCT c.id)  AS conversation_count
            FROM workspaces w
            LEFT JOIN users u ON u.workspace_id = w.id
            LEFT JOIN documents d ON d.workspace_id = w.id
            LEFT JOIN conversations c ON c.workspace_id = w.id
            GROUP BY w.id, w.name, w.slug, w.created_at
            ORDER BY w.created_at DESC
            """);
    }

    public List<Map<String, Object>> listUsers(UUID workspaceId) {
        return jdbc.queryForList(
            """
            SELECT id, email, full_name, role, active, created_at
            FROM users
            WHERE workspace_id = ?::uuid
            ORDER BY created_at DESC
            """, workspaceId.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private long queryLong(String sql, Object... args) {
        Long result = args.length == 0
                ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, args);
        return result != null ? result : 0L;
    }

    private long countRedisKeys(String pattern) {
        try {
            Set<String> keys = redis.keys(pattern);
            return keys != null ? keys.size() : 0L;
        } catch (Exception e) {
            log.warn("Redis key count failed: {}", e.getMessage());
            return 0L;
        }
    }
}
