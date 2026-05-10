package com.eakp.chat.dto;

import java.time.Instant;

public record ConversationDto(
    String  id,
    String  title,
    String  workspaceId,
    Instant createdAt,
    Instant updatedAt,
    int     messageCount
) {}
