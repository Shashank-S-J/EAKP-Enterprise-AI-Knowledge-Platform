package com.eakp.admin.controller;

import com.eakp.admin.dto.SystemHealthDto;
import com.eakp.admin.service.AnalyticsService;
import com.eakp.admin.service.AuditLogService;
import com.eakp.admin.service.WorkspaceAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only endpoints (ROLE_ADMIN required).
 * Enforced by @PreAuthorize + SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final AnalyticsService    analyticsService;
    private final WorkspaceAdminService workspaceAdminService;
    private final AuditLogService auditLogService;

    /** GET /api/v1/admin/health — full system health across all workspaces */
    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public SystemHealthDto systemHealth() {
        return analyticsService.getSystemHealth();
    }

    /** GET /api/v1/admin/workspaces — list all workspaces with stats */
    @GetMapping("/workspaces")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listWorkspaces() {
        return analyticsService.listWorkspaces();
    }

    /** GET /api/v1/admin/workspaces/{id}/users */
    @GetMapping("/workspaces/{id}/users")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> listUsers(@PathVariable UUID id) {
        return analyticsService.listUsers(id);
    }

    /** POST /api/v1/admin/workspaces/{id}/cache/evict — force clear semantic cache */
    @PostMapping("/workspaces/{id}/cache/evict")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void evictCache(@PathVariable UUID id) {
        workspaceAdminService.evictSemanticCache(id);
        auditLogService.logAction("CACHE_EVICT", id);
        log.info("Admin evicted semantic cache for workspace={}", id);
    }

    /** POST /api/v1/admin/workspaces/{id}/users/{userId}/deactivate */
    @PostMapping("/workspaces/{id}/users/{userId}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> deactivateUser(
            @PathVariable UUID id,
            @PathVariable UUID userId) {
        workspaceAdminService.deactivateUser(userId, id);
        auditLogService.logAction("USER_DEACTIVATE", userId, "workspace=" + id);
        return Map.of("message", "User deactivated", "userId", userId.toString());
    }

    /** POST /api/v1/admin/workspaces/{id}/users/{userId}/activate */
    @PostMapping("/workspaces/{id}/users/{userId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> activateUser(
            @PathVariable UUID id,
            @PathVariable UUID userId) {
        workspaceAdminService.activateUser(userId, id);
        auditLogService.logAction("USER_ACTIVATE", userId, "workspace=" + id);
        return Map.of("message", "User activated", "userId", userId.toString());
    }

    /** GET /api/v1/admin/workspaces/{id}/rag-quality — quality for specific workspace */
    @GetMapping("/workspaces/{id}/rag-quality")
    @PreAuthorize("hasRole('ADMIN')")
    public Object workspaceRagQuality(@PathVariable UUID id) {
        return analyticsService.getRagQuality(id);
    }
}
