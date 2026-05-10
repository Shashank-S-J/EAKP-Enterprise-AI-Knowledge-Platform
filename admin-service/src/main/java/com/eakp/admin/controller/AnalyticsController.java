package com.eakp.admin.controller;

import com.eakp.admin.dto.DocumentStatsDto;
import com.eakp.admin.dto.RagQualityDto;
import com.eakp.admin.dto.UsageStatsDto;
import com.eakp.admin.dto.WorkspaceStatsDto;
import com.eakp.admin.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Analytics endpoints — readable by any authenticated workspace member.
 * Data is always scoped to the caller's workspaceId (from JWT).
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /** GET /api/v1/analytics/overview — workspace counts summary */
    @GetMapping("/overview")
    public WorkspaceStatsDto overview(Authentication auth) {
        return analyticsService.getWorkspaceStats(workspaceId(auth));
    }

    /** GET /api/v1/analytics/rag-quality — faithfulness scores and hallucination rate */
    @GetMapping("/rag-quality")
    public RagQualityDto ragQuality(Authentication auth) {
        return analyticsService.getRagQuality(workspaceId(auth));
    }

    /** GET /api/v1/analytics/usage?days=30 — queries per day, top questions */
    @GetMapping("/usage")
    public UsageStatsDto usage(
            @RequestParam(defaultValue = "30") int days,
            Authentication auth) {
        return analyticsService.getUsageStats(workspaceId(auth), days);
    }

    /** GET /api/v1/analytics/documents — document status breakdown, top docs */
    @GetMapping("/documents")
    public DocumentStatsDto documents(Authentication auth) {
        return analyticsService.getDocumentStats(workspaceId(auth));
    }

    private UUID workspaceId(Authentication auth) {
        Object c = auth.getCredentials();
        return c instanceof UUID u ? u : UUID.fromString(c.toString());
    }
}
