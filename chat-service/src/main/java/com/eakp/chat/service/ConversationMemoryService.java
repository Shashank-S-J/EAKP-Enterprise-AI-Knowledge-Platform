package com.eakp.chat.service;

import com.eakp.chat.model.Conversation;
import com.eakp.chat.model.Message;
import com.eakp.chat.repository.ConversationRepository;
import com.eakp.chat.repository.MessageRepository;
import com.eakp.common.model.MessageRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages conversation history with automatic summarisation.
 *
 * Strategy:
 *   - Keep the last N messages verbatim (recent context)
 *   - Once we exceed the threshold, summarise older messages
 *     using the LLM and store the summary in the Conversation entity
 *   - Inject: [SUMMARY]\n{summary}\n\n[RECENT]\n{last N messages}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationMemoryService {

    private final ConversationRepository          conversationRepository;
    private final MessageRepository               messageRepository;
    private final ConversationSummarisationService summarisationService;

    @Value("${app.rag.max-conversation-messages:10}")
    private int maxMessages;

    @Value("${app.rag.summarize-after-messages:8}")
    private int summarizeThreshold;

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public Conversation createConversation(UUID userId,
                                           UUID workspaceId,
                                           String title) {
        Conversation conv = Conversation.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .title(title != null ? title : "New conversation")
                .build();
        return conversationRepository.save(conv);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Conversation getConversation(UUID conversationId,
                                         UUID workspaceId) {
        return conversationRepository
                .findByIdAndWorkspaceId(conversationId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Conversation not found: " + conversationId));
    }

    /**
     * Build the history string to inject into the LLM prompt.
     * Handles summarisation automatically.
     */
    @Transactional(readOnly = true)
    public String buildHistoryPrompt(UUID conversationId, UUID workspaceId) {
        Conversation conv = getConversation(conversationId, workspaceId);
        List<Message> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId);

        if (messages.isEmpty()) return "";

        // If within threshold, return all messages verbatim
        if (messages.size() <= summarizeThreshold) {
            return formatMessages(messages);
        }

        // Split: old (to be summarised) + recent (verbatim)
        int          recentCount    = maxMessages / 2;
        List<Message> oldMessages    = messages.subList(
                0, messages.size() - recentCount);
        List<Message> recentMessages = messages.subList(
                messages.size() - recentCount, messages.size());

        StringBuilder history = new StringBuilder();

        // Include persisted summary if it exists, otherwise summarise on the fly
        String summary = conv.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = summarisationService.summarise(oldMessages);
        }

        history.append("[CONVERSATION SUMMARY]\n")
               .append(summary)
               .append("\n\n[RECENT MESSAGES]\n")
               .append(formatMessages(recentMessages));

        return history.toString();
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    @Transactional
    public Message appendUserMessage(UUID conversationId,
                                      UUID workspaceId,
                                      String content) {
        Conversation conv = getConversation(conversationId, workspaceId);
        Message msg = Message.builder()
                .conversation(conv)
                .role(MessageRole.USER.name())
                .content(content)
                .build();
        Message saved = messageRepository.save(msg);

        // Trigger summarisation asynchronously via separate bean (proper AOP proxy)
        summarisationService.maybeSummariseAsync(conversationId);
        return saved;
    }

    @Transactional
    public Message appendAssistantMessage(UUID conversationId,
                                           UUID workspaceId,
                                           String content,
                                           List<Message.SourceReference> sources,
                                           Double faithfulness) {
        Conversation conv = getConversation(conversationId, workspaceId);

        // Auto-title: use a truncation of the first user message
        if ("New conversation".equals(conv.getTitle())) {
            messageRepository.findFirstByConversationIdAndRoleOrderByCreatedAtAsc(
                    conversationId, MessageRole.USER.name()).ifPresent(first -> {
                String title = first.getContent();
                conv.setTitle(title.length() > 60
                        ? title.substring(0, 60) + "..." : title);
                conversationRepository.save(conv);
            });
        }

        Message msg = Message.builder()
                .conversation(conv)
                .role(MessageRole.ASSISTANT.name())
                .content(content)
                .sources(sources)
                .faithfulness(faithfulness != null
                        ? java.math.BigDecimal.valueOf(faithfulness) : null)
                .build();
        return messageRepository.save(msg);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }
}
