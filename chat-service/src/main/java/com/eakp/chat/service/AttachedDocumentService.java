package com.eakp.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Looks up documents attached to a specific conversation (ChatGPT-style
 * per-message attachments) without coupling the chat module to the
 * ingestion-service's JPA entities. We read directly from the shared
 * Postgres `documents` table.
 *
 * <p>
 * "Attached" means a document row whose {@code conversation_id}
 * column equals the supplied conversation. Plain workspace-wide
 * documents (NULL conversation_id) are not returned here.
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachedDocumentService {

    private final JdbcTemplate jdbcTemplate;

    /** Document IDs attached to this conversation, READY only. */
    public List<UUID> attachedDocumentIds(UUID conversationId, UUID workspaceId) {
        if (conversationId == null)
            return List.of();
        try {
            return jdbcTemplate.queryForList(
                    """
                            SELECT id FROM documents
                            WHERE conversation_id = ?::uuid
                              AND workspace_id    = ?::uuid
                              AND status          = 'READY'
                            """,
                    UUID.class,
                    conversationId.toString(), workspaceId.toString());
        } catch (Exception e) {
            log.warn("Could not load attached documents for conv={}: {}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Filenames attached to this conversation (READY only) — used for the LLM hint.
     * Excludes PENDING/PROCESSING/FAILED so the prompt never claims an
     * in-flight upload is available context.
     */
    public List<String> attachedFilenames(UUID conversationId, UUID workspaceId) {
        if (conversationId == null)
            return List.of();
        try {
            return jdbcTemplate.queryForList(
                    """
                            SELECT filename FROM documents
                            WHERE conversation_id = ?::uuid
                              AND workspace_id    = ?::uuid
                              AND status          = 'READY'
                            ORDER BY created_at DESC
                            """,
                    String.class,
                    conversationId.toString(), workspaceId.toString());
        } catch (Exception e) {
            log.warn("Could not load attached filenames for conv={}: {}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * The single most-recently uploaded READY document attached to this
     * conversation, as {@code [docId, filename]}. Used when a user query is a
     * bare reference ("explain", "summarize this", "tldr", "what does it
     * say") — we narrow retrieval to this one document so a freshly-ingested
     * file is what gets answered, not whichever earlier doc happens to embed
     * best.
     *
     * @return {@code Optional.empty()} when no READY docs are attached.
     */
    public java.util.Optional<DocRef> mostRecentAttachment(UUID conversationId, UUID workspaceId) {
        if (conversationId == null) return java.util.Optional.empty();
        try {
            List<DocRef> rows = jdbcTemplate.query(
                    """
                            SELECT id, filename FROM documents
                            WHERE conversation_id = ?::uuid
                              AND workspace_id    = ?::uuid
                              AND status          = 'READY'
                            ORDER BY created_at DESC
                            LIMIT 1
                            """,
                    (rs, n) -> new DocRef(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("filename")),
                    conversationId.toString(), workspaceId.toString());
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        } catch (Exception e) {
            log.warn("Could not load most-recent attachment for conv={}: {}",
                    conversationId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** Lightweight reference to an attached document. */
    public record DocRef(UUID id, String filename) {}

    /**
     * Filenames of documents attached to this conversation whose ingestion is
     * NOT yet complete (PENDING or PROCESSING). Used so we can tell the user
     * "your file is still being processed" instead of the generic
     * "no information" reply when they ask immediately after uploading.
     */
    public List<String> pendingAttachedFilenames(UUID conversationId, UUID workspaceId) {
        if (conversationId == null)
            return List.of();
        try {
            return jdbcTemplate.queryForList(
                    """
                            SELECT filename FROM documents
                            WHERE conversation_id = ?::uuid
                              AND workspace_id    = ?::uuid
                              AND status IN ('PENDING','PROCESSING')
                            ORDER BY created_at DESC
                            """,
                    String.class,
                    conversationId.toString(), workspaceId.toString());
        } catch (Exception e) {
            log.warn("Could not load pending filenames for conv={}: {}",
                    conversationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Count how many chunks already exist for the documents attached to this
     * conversation. Lets the chat-service decide whether to wait briefly for
     * an in-flight ingestion to finish before answering.
     */
    public int chunkCountForConversation(UUID conversationId, UUID workspaceId) {
        if (conversationId == null)
            return 0;
        try {
            Integer n = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)::int FROM document_chunks dc
                            JOIN documents d ON d.id = dc.document_id
                            WHERE d.conversation_id = ?::uuid
                              AND d.workspace_id    = ?::uuid
                            """,
                    Integer.class,
                    conversationId.toString(), workspaceId.toString());
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}