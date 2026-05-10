package com.eakp.gateway.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    long   expiresAt,     // epoch millis
    String userId,
    String email,
    String fullName,
    String role,
    String workspaceId
) {}
