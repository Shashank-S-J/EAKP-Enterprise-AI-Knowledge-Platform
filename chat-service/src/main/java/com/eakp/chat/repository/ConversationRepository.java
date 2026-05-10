package com.eakp.chat.repository;

import com.eakp.chat.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository
        extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(
            UUID userId, UUID workspaceId);

    Optional<Conversation> findByIdAndWorkspaceId(
            UUID id, UUID workspaceId);

    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId " +
           "AND c.workspaceId = :workspaceId ORDER BY c.updatedAt DESC")
    List<Conversation> findRecentByUser(UUID userId, UUID workspaceId);
}
