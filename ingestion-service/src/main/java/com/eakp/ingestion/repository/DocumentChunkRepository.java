package com.eakp.ingestion.repository;

import com.eakp.ingestion.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(UUID documentId);

    long countByDocumentId(UUID documentId);

    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.documentId = :documentId")
    void deleteByDocumentId(UUID documentId);

    @Query("SELECT COUNT(c) FROM DocumentChunk c WHERE c.workspaceId = :workspaceId")
    long countByWorkspaceId(UUID workspaceId);
}
