package com.eakp.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SemanticCacheServiceTest {

    @Mock JdbcTemplate    jdbcTemplate;
    @Mock EmbeddingModel  embeddingModel;

    SemanticCacheService cacheService;

    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        cacheService = new SemanticCacheService(embeddingModel, jdbcTemplate);
        ReflectionTestUtils.setField(cacheService, "threshold",     0.92);
        ReflectionTestUtils.setField(cacheService, "cacheTtlHours", 24);
    }

    @Test
    void findSimilar_returnsEmpty_whenNoCacheEntries() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
                .thenReturn(List.of());

        Optional<SemanticCacheService.CachedAnswer> result =
                cacheService.findSimilar("What is RAG?", workspaceId);

        assertThat(result).isEmpty();
    }

    @Test
    void findSimilar_returnsHit_whenSimilarQueryExists() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(anyString())).thenReturn(embedding);

        UUID cacheId = UUID.randomUUID();
        Map<String, Object> row = new HashMap<>();
        row.put("id", cacheId);
        row.put("query", "What is RAG?");
        row.put("answer", "RAG stands for Retrieval-Augmented Generation.");
        row.put("hit_count", 0);
        row.put("similarity", 0.98);

        when(jdbcTemplate.queryForList(anyString(), any(), any(), any()))
                .thenReturn(List.of(row));

        Optional<SemanticCacheService.CachedAnswer> result =
                cacheService.findSimilar("What is RAG?", workspaceId);

        assertThat(result).isPresent();
        assertThat(result.get().answer())
                .contains("Retrieval-Augmented Generation");
        assertThat(result.get().similarityScore()).isGreaterThan(0.92);
    }

    @Test
    void store_insertsIntoPgvector() {
        float[] embedding = {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(anyString())).thenReturn(embedding);

        cacheService.store("Test query", workspaceId,
                "Test answer", List.of("file.pdf"));

        verify(jdbcTemplate, times(1))
                .update(contains("INSERT INTO semantic_cache"),
                        any(), any(), any(), any());
    }

    @Test
    void evictWorkspace_deletesAllWorkspaceEntries() {

        cacheService.evictWorkspace(workspaceId);

        verify(jdbcTemplate).update(
                contains("DELETE FROM semantic_cache"),
                eq(workspaceId.toString()));
    }
}
