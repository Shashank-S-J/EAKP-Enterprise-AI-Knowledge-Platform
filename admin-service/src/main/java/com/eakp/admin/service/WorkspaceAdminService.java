package com.eakp.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceAdminService {

    private final JdbcTemplate jdbc;

    public void evictSemanticCache(UUID workspaceId) {
        int deleted = jdbc.update(
                "DELETE FROM semantic_cache WHERE workspace_id = ?::uuid",
                workspaceId.toString());
        if (deleted > 0) {
            log.info("Evicted {} cache entries for workspace={}", deleted, workspaceId);
        }
    }

    public void deactivateUser(UUID userId, UUID workspaceId) {
        int rows = jdbc.update(
            "UPDATE users SET active = false, updated_at = NOW() " +
            "WHERE id = ?::uuid AND workspace_id = ?::uuid",
            userId.toString(), workspaceId.toString());
        if (rows == 0) throw new IllegalArgumentException("User not found: " + userId);
        log.info("Deactivated user={} in workspace={}", userId, workspaceId);
    }

    public void activateUser(UUID userId, UUID workspaceId) {
        jdbc.update(
            "UPDATE users SET active = true, updated_at = NOW() " +
            "WHERE id = ?::uuid AND workspace_id = ?::uuid",
            userId.toString(), workspaceId.toString());
    }
}
