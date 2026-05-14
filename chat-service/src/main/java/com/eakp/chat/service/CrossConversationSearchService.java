package com.eakp.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cross-conversation memory.
 *
 * <p>Searches the workspace's <em>past</em> messages (not the current chat)
 * so the assistant can answer requests like:</p>
 * <ul>
 *   <li>"summarize yesterday's discussion"</li>
 *   <li>"what did we conclude last week about pricing?"</li>
 *   <li>"the doc I asked about earlier" (when the user is now in a new chat)</li>
 * </ul>
 *
 * <p>Implementation: Postgres FTS over <code>messages.content</code>, scoped
 * to the workspace, optionally filtered by a temporal window parsed from
 * natural-language phrases in the user's query.</p>
 *
 * <p>No new dependencies: uses the GIN FTS index added in
 * {@code infra/sql/migrate-cross-conversation-search.sql}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossConversationSearchService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.rag.cross-conv.top-k:5}")
    private int topK;

    @Value("${app.rag.cross-conv.snippet-chars:280}")
    private int snippetChars;

    /** Snippet from a previous conversation. */
    public record PastMessage(
            UUID    conversationId,
            String  conversationTitle,
            Instant createdAt,
            String  role,
            String  snippet) {
        public String toPromptLine() {
            return "  [%s | %s | %s]\n    %s".formatted(
                    DateTimeFormatter.ISO_INSTANT.format(createdAt),
                    role,
                    conversationTitle != null ? conversationTitle : "untitled",
                    snippet);
        }
    }

    /**
     * Retrieve past-message snippets relevant to the query.
     *
     * @param query          The user's question (raw).
     * @param workspaceId    Workspace scope (tenant isolation).
     * @param currentConvId  Conversation to EXCLUDE — we want history, not the current chat.
     * @return Up to {@code topK} snippets ordered by relevance (FTS rank), then recency.
     */
    public List<PastMessage> findRelevantPastMessages(String query,
                                                      UUID workspaceId,
                                                      UUID currentConvId) {
        if (query == null || query.isBlank()) return List.of();

        TemporalScope scope = parseTemporalScope(query);
        // Strip temporal words so they don't dominate FTS ranking
        String ftsQuery = scope.cleanedQuery();
        if (ftsQuery.isBlank()) ftsQuery = query;

        try {
            StringBuilder sql = new StringBuilder("""
                SELECT m.conversation_id,
                       m.role,
                       m.content,
                       m.created_at,
                       c.title,
                       ts_rank(to_tsvector('english', m.content),
                               plainto_tsquery('english', ?)) AS score
                FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE c.workspace_id = ?::uuid
                  AND to_tsvector('english', m.content)
                      @@ plainto_tsquery('english', ?)
                """);
            List<Object> args = new ArrayList<>();
            args.add(ftsQuery);
            args.add(workspaceId.toString());
            args.add(ftsQuery);

            if (currentConvId != null) {
                sql.append("  AND m.conversation_id <> ?::uuid\n");
                args.add(currentConvId.toString());
            }
            if (scope.since() != null) {
                sql.append("  AND m.created_at >= ?\n");
                args.add(java.sql.Timestamp.from(scope.since()));
            }
            if (scope.until() != null) {
                sql.append("  AND m.created_at < ?\n");
                args.add(java.sql.Timestamp.from(scope.until()));
            }
            sql.append("ORDER BY score DESC, m.created_at DESC LIMIT ?");
            args.add(topK);

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    sql.toString(), args.toArray());

            List<PastMessage> out = new ArrayList<>(rows.size());
            for (Map<String, Object> r : rows) {
                String content = String.valueOf(r.get("content"));
                String snippet = content.length() > snippetChars
                        ? content.substring(0, snippetChars) + "…"
                        : content;
                Instant createdAt = ((java.sql.Timestamp) r.get("created_at")).toInstant();
                out.add(new PastMessage(
                        (UUID) r.get("conversation_id"),
                        (String) r.get("title"),
                        createdAt,
                        (String) r.get("role"),
                        snippet));
            }
            if (!out.isEmpty()) {
                log.info("Cross-conv search: found {} relevant past messages (scope={})",
                        out.size(), scope);
            }
            return out;
        } catch (Exception e) {
            log.warn("Cross-conv FTS failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Temporal scope parsing ───────────────────────────────────────────────

    /** Parsed time window. {@code null} bound = open-ended. */
    public record TemporalScope(Instant since, Instant until, String cleanedQuery) {}

    private static final Pattern P_YESTERDAY     = Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TODAY         = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_LAST_WEEK     = Pattern.compile("\\blast\\s+week\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_THIS_WEEK     = Pattern.compile("\\bthis\\s+week\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_LAST_MONTH    = Pattern.compile("\\blast\\s+month\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_EARLIER       = Pattern.compile("\\bearlier\\b|\\bprevious(ly)?\\b|\\bbefore\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_N_DAYS_AGO    = Pattern.compile("\\b(\\d{1,2})\\s+days?\\s+ago\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Extract a temporal window from natural language. Public for testing.
     * Uses system default zone — good enough for "yesterday"-grade resolution.
     */
    public TemporalScope parseTemporalScope(String query) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        String cleaned = query;

        Instant since = null, until = null;

        if (P_YESTERDAY.matcher(query).find()) {
            since = today.minusDays(1).atStartOfDay(zone).toInstant();
            until = today.atStartOfDay(zone).toInstant();
            cleaned = P_YESTERDAY.matcher(cleaned).replaceAll("");
        } else if (P_TODAY.matcher(query).find()) {
            since = today.atStartOfDay(zone).toInstant();
            cleaned = P_TODAY.matcher(cleaned).replaceAll("");
        } else if (P_LAST_WEEK.matcher(query).find()) {
            since = today.minusDays(today.getDayOfWeek().getValue() + 6)
                    .atStartOfDay(zone).toInstant();
            until = today.minusDays(today.getDayOfWeek().getValue() - 1)
                    .atStartOfDay(zone).toInstant();
            cleaned = P_LAST_WEEK.matcher(cleaned).replaceAll("");
        } else if (P_THIS_WEEK.matcher(query).find()) {
            since = today.minusDays(today.getDayOfWeek().getValue() - 1)
                    .atStartOfDay(zone).toInstant();
            cleaned = P_THIS_WEEK.matcher(cleaned).replaceAll("");
        } else if (P_LAST_MONTH.matcher(query).find()) {
            since = today.minusMonths(1).withDayOfMonth(1)
                    .atStartOfDay(zone).toInstant();
            until = today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
            cleaned = P_LAST_MONTH.matcher(cleaned).replaceAll("");
        } else {
            var m = P_N_DAYS_AGO.matcher(query);
            if (m.find()) {
                int days = Integer.parseInt(m.group(1));
                since = Instant.now().minus(Duration.ofDays(days + 1L));
                until = Instant.now().minus(Duration.ofDays((long) days - 1));
                cleaned = m.replaceAll("");
            } else if (P_EARLIER.matcher(query).find()) {
                // Open-ended past — just remove the marker word
                cleaned = P_EARLIER.matcher(cleaned).replaceAll("");
            }
        }
        return new TemporalScope(since, until, cleaned.trim().replaceAll("\\s+", " "));
    }
}