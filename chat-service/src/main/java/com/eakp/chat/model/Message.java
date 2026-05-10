package com.eakp.chat.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false, length = 20)
    private String role;   // USER | ASSISTANT | SYSTEM

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * JSON array of cited sources:
     * [{"chunkId": "uuid", "snippet": "...", "source": "filename.pdf"}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<SourceReference> sources;

    @Column(precision = 4, scale = 3)
    private BigDecimal faithfulness;  // 0.000 – 1.000

    /** User feedback: "positive" or "negative" (null if no feedback given) */
    @Column(length = 20)
    private String feedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { createdAt = Instant.now(); }

    // ── Nested value objects ─────────────────────────────────────────────

    public record SourceReference(
        String chunkId,
        String snippet,
        String source
    ) {}
}
