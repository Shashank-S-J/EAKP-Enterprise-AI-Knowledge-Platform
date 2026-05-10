package com.eakp.ingestion.pipeline;

import com.eakp.ingestion.model.DocumentChunk;
import com.eakp.ingestion.repository.DocumentChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Writes document chunks and their embeddings into the document_chunks table.
 *
 * 1. Saves chunk metadata + content via JPA (document_chunks table)
 * 2. Updates the pgvector embedding column directly via JDBC
 *    using pre-computed embeddings (avoids re-embedding)
 *
 * Both writes happen in a single transaction so they're consistent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorStoreWriter {

    private final DocumentChunkRepository  chunkRepository;
    private final JdbcTemplate             jdbcTemplate;

    /**
     * Persist chunks and their embeddings.
     * Called after embedding is complete.
     *
     * @param chunks      TextChunk list from TextChunker
     * @param embeddings  Corresponding float[] arrays from EmbeddingService
     * @param documentId  Parent document UUID
     * @param workspaceId Workspace UUID (for tenant isolation)
     * @param filename    Original filename (stored in metadata)
     * @return List of persisted DocumentChunk entities
     */
    @Transactional
    public List<DocumentChunk> write(List<TextChunker.TextChunk> chunks,
                                      List<float[]> embeddings,
                                      UUID documentId,
                                      UUID workspaceId,
                                      String filename) {

        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                "Chunks (%d) and embeddings (%d) count mismatch"
                        .formatted(chunks.size(), embeddings.size()));
        }

        List<DocumentChunk> savedChunks = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.TextChunk chunk     = chunks.get(i);

            // Build JPA entity
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source",      filename);
            metadata.put("document_id", documentId.toString());
            metadata.put("chunk_index", chunk.index());
            metadata.put("workspace_id", workspaceId.toString());

            DocumentChunk entity = DocumentChunk.builder()
                    .documentId(documentId)
                    .workspaceId(workspaceId)
                    .content(chunk.content())
                    .chunkIndex(chunk.index())
                    .tokenCount(chunk.estimatedTokens())
                    .metadata(metadata)
                    .build();

            savedChunks.add(chunkRepository.save(entity));
        }

        // Write pre-computed embeddings directly via JDBC (avoids re-embedding)
        writeEmbeddingsDirectly(savedChunks, embeddings);

        log.info("Wrote {} chunks to vector store for document={} workspace={}",
                chunks.size(), documentId, workspaceId);

        return savedChunks;
    }

    /**
     * Delete all vectors for a document (used when re-ingesting or deleting).
     */
    @Transactional
    public void deleteByDocument(UUID documentId) {
        // Delete from document_chunks table (JPA handles the query)
        chunkRepository.deleteByDocumentId(documentId);

        log.info("Deleted all chunks for document={}", documentId);
    }

    // ── Direct JDBC embedding insert ──────────────────────────────────────────

    /**
     * Insert pre-computed embeddings directly into the document_chunks table
     * using JDBC. This avoids re-embedding (which would waste API calls).
     *
     * pgvector accepts float arrays as strings: '[0.1,0.2,...]'
     */
    private void writeEmbeddingsDirectly(List<DocumentChunk> chunks,
                                          List<float[]> embeddings) {
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk     = chunks.get(i);
            float[]       embedding = embeddings.get(i);

            jdbcTemplate.update(
                "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?::uuid",
                toVectorString(embedding),
                chunk.getId().toString()
            );
        }
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
