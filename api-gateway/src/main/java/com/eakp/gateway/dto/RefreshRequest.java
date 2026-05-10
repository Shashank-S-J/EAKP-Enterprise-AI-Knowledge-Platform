package com.eakp.gateway.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
    @NotBlank(message = "Refresh token is required")
    String refreshToken
) {}
