package com.eakp.gateway.dto;

public record LogoutRequest(
    String refreshToken  // optional
) {}
