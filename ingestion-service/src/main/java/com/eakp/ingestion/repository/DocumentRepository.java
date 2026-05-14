package com.eakp.ingestion.repository;

import com.eakp.ingestion.model.Document;
import com.eakp.ingestion.model.Document.DocumentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<Document> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    Optional<Document> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    List<Document> findByStatus(DocumentStatus status);

    long countByWorkspaceIdAndStatus(UUID workspaceId, DocumentStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.status = :status, d.errorMsg = :error, " +
           "d.chunkCount = :chunkCount WHERE d.id = :id")
    void updateStatus(UUID id, DocumentStatus status,
                      String error, Integer chunkCount);

    @Query("SELECT COUNT(d) FROM Document d WHERE d.workspaceId = :workspaceId")
    long countByWorkspaceId(UUID workspaceId);
}
