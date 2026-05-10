package com.eakp.chat;

import com.eakp.chat.model.Conversation;
import com.eakp.chat.service.ChatService;
import com.eakp.chat.service.ConversationMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ChatService chatService;

    @MockBean
    ConversationMemoryService memoryService;

    private static final UUID WORKSPACE_ID    =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setup() {
        Conversation mockConv = new Conversation();
        mockConv.setId(CONVERSATION_ID);
        mockConv.setWorkspaceId(WORKSPACE_ID);
        mockConv.setTitle("Test conversation");

        when(memoryService.createConversation(any(), any(), any()))
                .thenReturn(mockConv);
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void createConversation_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"My chat\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void streamChat_returnsSseStream() throws Exception {
        when(chatService.streamAnswer(anyString(), any(), any(), any()))
                .thenReturn(Flux.just("Hello", " world", "[DONE]"));

        mockMvc.perform(get("/api/v1/chat/stream")
                .param("message", "What is RAG?")
                .param("conversationId", CONVERSATION_ID.toString())
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(
                    MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    @Test
    void unauthenticated_request_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/chat/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
