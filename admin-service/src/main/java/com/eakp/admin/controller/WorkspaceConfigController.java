package com.eakp.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Workspace configuration endpoints — scoped to the caller's workspace.
 */
@RestController
@RequestMapping("/api/v1/workspaces/current/config")
@RequiredArgsConstructor
@Slf4j
public class WorkspaceConfigController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> getConfig(Authentication auth) {
        UUID wsId = workspaceId(auth);
        try {
            return jdbc.queryForMap(
                "SELECT name AS \"workspaceName\", slug, " +
                "COALESCE(default_model, 'gpt-4o-mini') AS \"defaultModel\", " +
                "COALESCE(chunk_size, 512) AS \"chunkSize\", " +
                "COALESCE(chunk_overlap, 128) AS \"chunkOverlap\", " +
                "COALESCE(embedding_model, 'text-embedding-3-small') AS \"embeddingModel\" " +
                "FROM workspaces WHERE id = ?::uuid",
                wsId.toString());
        } catch (Exception e) {
            log.warn("Workspace config not found for {}, returning defaults", wsId);
            return Map.of(
                "workspaceName", "",
                "slug", "",
                "defaultModel", "gpt-4o-mini",
                "chunkSize", 512,
                "chunkOverlap", 128,
                "embeddingModel", "text-embedding-3-small"
            );
        }
    }

    @PutMapping
    public Map<String, String> updateConfig(Authentication auth, @RequestBody Map<String, Object> body) {
        UUID wsId = workspaceId(auth);
        String name = (String) body.getOrDefault("workspaceName", "");
        String slug = (String) body.getOrDefault("slug", "");
        String model = (String) body.getOrDefault("defaultModel", "gpt-4o-mini");
        int chunkSize = body.get("chunkSize") instanceof Number n ? n.intValue() : 512;
        int chunkOverlap = body.get("chunkOverlap") instanceof Number n ? n.intValue() : 128;
        String embModel = (String) body.getOrDefault("embeddingModel", "text-embedding-3-small");

        // Input validation
        if (name.length() > 255) throw new IllegalArgumentException("Workspace name too long");
        if (slug.length() > 100) throw new IllegalArgumentException("Slug too long");
        if (!slug.matches("^[a-z0-9-]*$")) throw new IllegalArgumentException("Slug must be lowercase letters, numbers, hyphens only");
        if (chunkSize < 128 || chunkSize > 4096) throw new IllegalArgumentException("Chunk size must be between 128 and 4096");
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) throw new IllegalArgumentException("Chunk overlap must be between 0 and chunk size");

        try {
            jdbc.update(
                "UPDATE workspaces SET name = ?, slug = ?, default_model = ?, " +
                "chunk_size = ?, chunk_overlap = ?, embedding_model = ?, updated_at = NOW() " +
                "WHERE id = ?::uuid",
                name, slug, model, chunkSize, chunkOverlap, embModel, wsId.toString());
        } catch (Exception e) {
            log.error("Failed to update workspace config: {}", e.getMessage());
            throw new RuntimeException("Failed to update configuration");
        }

        log.info("Updated workspace config for {}", wsId);
        return Map.of("message", "Configuration updated");
    }

    private UUID workspaceId(Authentication auth) {
        Object c = auth.getCredentials();
        return c instanceof UUID u ? u : UUID.fromString(c.toString());
    }
}

