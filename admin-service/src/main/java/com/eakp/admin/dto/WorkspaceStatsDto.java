package com.eakp.admin.dto;

// ── Workspace Stats ────────────────────────────────────────────────────────────
public record WorkspaceStatsDto(
    String workspaceId,
    long   totalDocuments,
    long   readyDocuments,
    long   totalChunks,
    long   totalConversations,
    long   totalMessages,
    long   cacheEntries
) {}
