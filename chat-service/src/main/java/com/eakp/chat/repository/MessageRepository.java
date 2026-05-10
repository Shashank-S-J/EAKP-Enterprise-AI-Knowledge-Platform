package com.eakp.chat.repository;

import com.eakp.chat.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    Optional<Message> findFirstByConversationIdAndRoleOrderByCreatedAtAsc(
            UUID conversationId, String role);

    long countByConversationId(UUID conversationId);

    @Query(value = "SELECT * FROM messages WHERE conversation_id = ?1 ORDER BY created_at DESC LIMIT ?2",
           nativeQuery = true)
    List<Message> findTopNByConversationIdOrderByCreatedAtDesc(UUID conversationId, int limit);
}
