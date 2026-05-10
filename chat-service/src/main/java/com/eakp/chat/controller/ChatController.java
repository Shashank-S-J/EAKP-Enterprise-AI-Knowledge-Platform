package com.eakp.chat.controller;

import com.eakp.chat.dto.ConversationDto;
import com.eakp.chat.dto.MessageDto;
import com.eakp.chat.dto.NewConversationRequest;
import com.eakp.chat.dto.SourceDto;
import com.eakp.chat.model.Conversation;
import com.eakp.chat.model.Message;
import com.eakp.chat.repository.ConversationRepository;
import com.eakp.chat.repository.MessageRepository;
import com.eakp.chat.service.ChatService;
import com.eakp.chat.service.ConversationMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatService               chatService;
    private final ConversationMemoryService memoryService;
    private final ConversationRepository    conversationRepo;
    private final MessageRepository         messageRepo;

    // ── SSE Streaming endpoint ────────────────────────────────────────────────

    /**
     * POST /api/v1/chat/stream
     *
     * Accepts JSON body: { "conversationId": "...", "message": "..." }
     * Returns a Server-Sent Event stream.
     * Each event has type "token" until the final "done" event.
     *
     * SSE format:
     *   event: token\ndata: Hello\n\n
     *   event: token\ndata:  world\n\n
     *   event: done\ndata: [DONE]\n\n
     */
    @PostMapping(value = "/stream",
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestBody @jakarta.validation.Valid com.eakp.chat.dto.ChatRequest chatRequest,
            Authentication auth) {

        String message = chatRequest.message();
        String conversationId = chatRequest.conversationId();

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);
        UUID convId;
        try {
            convId = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data("Invalid conversation ID format")
                    .build());
        }


        log.info("SSE stream: user={} ws={} conv={}",
                auth.getName(), workspaceId, convId);

        return chatService
                .streamAnswer(message, convId, workspaceId, userId)
                .map(token -> {
                    if ("[DONE]".equals(token)) {
                        return ServerSentEvent.<String>builder()
                                .event("done")
                                .data("[DONE]")
                                .build();
                    }
                    return ServerSentEvent.<String>builder()
                            .event("token")
                            .data(token)
                            .build();
                })
                .onErrorResume(e -> {
                    log.error("Stream error", e);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("Stream error: " + e.getMessage())
                            .build());
                });
    }

    // ── Conversation management ───────────────────────────────────────────────

    /**
     * POST /api/v1/chat/conversations
     * Create a new conversation. Returns the conversationId to use for streaming.
     */
    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto createConversation(
            @RequestBody(required = false) NewConversationRequest request,
            Authentication auth) {

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);
        String title     = request != null ? request.title() : null;

        Conversation conv = memoryService.createConversation(
                userId, workspaceId, title);

        return toDto(conv, 0);
    }

    /**
     * GET /api/v1/chat/conversations
     * List all conversations for the authenticated user in their workspace.
     */
    @GetMapping("/conversations")
    public List<ConversationDto> listConversations(Authentication auth) {
        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);

        return conversationRepo
                .findByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(
                        userId, workspaceId)
                .stream()
                .map(c -> toDto(c,
                        (int) messageRepo.countByConversationId(c.getId())))
                .collect(Collectors.toList());
    }

    /**
     * GET /api/v1/chat/conversations/{id}/messages
     * Return all messages in a conversation (for history display).
     */
    @GetMapping("/conversations/{id}/messages")
    public List<MessageDto> getMessages(
            @PathVariable UUID id,
            Authentication auth) {

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);

        // Verify ownership — checks both workspace and user
        Conversation conv = memoryService.getConversation(id, workspaceId);
        if (!conv.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not own this conversation");
        }

        return messageRepo
                .findByConversationIdOrderByCreatedAtAsc(id)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * GET /api/v1/chat/conversations/{id}/export?format=json|markdown
     * Export a conversation with all messages.
     */
    @GetMapping("/conversations/{id}/export")
    public java.util.Map<String, Object> exportConversation(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "json") String format,
            Authentication auth) {

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);

        Conversation conv = memoryService.getConversation(id, workspaceId);
        if (!conv.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not own this conversation");
        }

        List<MessageDto> messages = messageRepo
                .findByConversationIdOrderByCreatedAtAsc(id)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());

        return java.util.Map.of(
                "title", conv.getTitle() != null ? conv.getTitle() : "Untitled",
                "exportedAt", java.time.Instant.now().toString(),
                "format", format,
                "messageCount", messages.size(),
                "messages", messages
        );
    }

    /**
     * DELETE /api/v1/chat/conversations/{id}
     * Delete a conversation and all its messages.
     */
    @DeleteMapping("/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConversation(
            @PathVariable UUID id,
            Authentication auth) {

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);
        Conversation conv = memoryService.getConversation(id, workspaceId);
        if (!conv.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not own this conversation");
        }
        conversationRepo.delete(conv);
        log.info("Deleted conversation {} for ws={}", id, workspaceId);
    }

    /**
     * PATCH /api/v1/chat/conversations/{id}
     * Rename a conversation.
     */
    @PatchMapping("/conversations/{id}")
    public ConversationDto renameConversation(
            @PathVariable UUID id,
            @RequestBody NewConversationRequest request,
            Authentication auth) {

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);
        Conversation conv = memoryService.getConversation(id, workspaceId);
        if (!conv.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not own this conversation");
        }
        if (request.title() != null && !request.title().isBlank()) {
            conv.setTitle(request.title().trim());
            conversationRepo.save(conv);
            log.info("Renamed conversation {} to '{}' for ws={}", id, conv.getTitle(), workspaceId);
        }
        return toDto(conv, (int) messageRepo.countByConversationId(conv.getId()));
    }

    /**
     * POST /api/v1/chat/conversations/{convId}/messages/{msgId}/feedback
     * Record user feedback (positive/negative) on an assistant message.
     */
    @PostMapping("/conversations/{convId}/messages/{msgId}/feedback")
    public java.util.Map<String, String> submitFeedback(
            @PathVariable UUID convId,
            @PathVariable UUID msgId,
            @RequestBody java.util.Map<String, String> body,
            Authentication auth) {

        UUID workspaceId = extractWorkspaceId(auth);
        UUID userId      = extractUserId(auth);

        // Verify ownership
        Conversation conv = memoryService.getConversation(convId, workspaceId);
        if (!conv.getUserId().equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "You do not own this conversation");
        }

        String rating = body.get("rating"); // "positive" or "negative"
        if (rating == null || (!rating.equals("positive") && !rating.equals("negative"))) {
            throw new IllegalArgumentException("Rating must be 'positive' or 'negative'");
        }

        // Update the message's feedback in DB
        messageRepo.findById(msgId).ifPresent(msg -> {
            msg.setFeedback(rating);
            messageRepo.save(msg);
        });

        log.info("Feedback recorded: conv={} msg={} rating={}", convId, msgId, rating);
        return java.util.Map.of("message", "Feedback recorded", "rating", rating);
    }

    // ── Security helpers ──────────────────────────────────────────────────────

    /**
     * workspaceId is stored in auth.getCredentials() by JwtAuthFilter —
     * same pattern as api-gateway so everything is consistent.
     */
    private UUID extractWorkspaceId(Authentication auth) {
        Object credentials = auth.getCredentials();
        if (credentials instanceof UUID uuid) return uuid;
        return UUID.fromString(credentials.toString());
    }

    /**
     * userId: extracted from the JWT 'userId' claim, stored in request attribute by JwtAuthFilter.
     * Falls back to deterministic UUID from email for backward compatibility.
     */
    private UUID extractUserId(Authentication auth) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            Object userId = attrs.getRequest().getAttribute("userId");
            if (userId instanceof UUID uuid) return uuid;
        }
        // Fallback: deterministic UUID from email (backward compat)
        return UUID.nameUUIDFromBytes(
                auth.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private ConversationDto toDto(Conversation c, int messageCount) {
        return new ConversationDto(
                c.getId().toString(),
                c.getTitle(),
                c.getWorkspaceId().toString(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                messageCount
        );
    }

    private MessageDto toDto(Message m) {
        List<SourceDto> sources = m.getSources() == null
                ? List.of()
                : m.getSources().stream()
                        .map(s -> new SourceDto(
                                s.chunkId(), s.snippet(), s.source()))
                        .collect(Collectors.toList());

        Double faithfulness = m.getFaithfulness() == null
                ? null : m.getFaithfulness().doubleValue();

        return new MessageDto(
                m.getId().toString(),
                m.getRole(),
                m.getContent(),
                sources,
                faithfulness,
                m.getCreatedAt()
        );
    }
}
