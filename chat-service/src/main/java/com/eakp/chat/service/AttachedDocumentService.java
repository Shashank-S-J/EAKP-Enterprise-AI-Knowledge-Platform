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
     * Filenames attached to this conversation (any status) — used for the LLM hint.
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