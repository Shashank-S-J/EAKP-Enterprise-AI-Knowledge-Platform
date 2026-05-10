package com.eakp.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Audit logging service for admin actions.
 * Records all privileged operations for compliance and security review.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Record an admin action asynchronously (non-blocking).
     */
    @Async
    public void logAction(String action, UUID targetId, String details) {
        String adminEmail = getCurrentAdminEmail();
        try {
            jdbcTemplate.update("""
                INSERT INTO audit_log (admin_email, action, target_id, details, created_at)
                VALUES (?, ?, ?::uuid, ?, NOW())
                """, adminEmail, action, targetId != null ? targetId.toString() : null, details);
        } catch (Exception e) {
            // Table may not exist yet — fall back to structured logging
            log.info("AUDIT | admin={} action={} target={} details={}",
                    adminEmail, action, targetId, details);
        }
    }

    public void logAction(String action, UUID targetId) {
        logAction(action, targetId, null);
    }

    private String getCurrentAdminEmail() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return auth != null ? auth.getName() : "system";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

