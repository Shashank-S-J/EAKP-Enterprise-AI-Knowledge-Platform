package com.eakp.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank(message = "Message cannot be blank")
    @Size(max = 10000, message = "Message too long (max 10,000 characters)")
    String message,

    @NotBlank(message = "Conversation ID is required")
    String conversationId
) {}
