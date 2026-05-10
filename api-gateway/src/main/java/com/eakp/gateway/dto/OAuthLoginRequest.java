package com.eakp.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OAuthLoginRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email")
    String email,

    @NotBlank(message = "Full name is required")
    String fullName,

    @NotBlank(message = "Provider is required")
    String provider,  // GOOGLE | GITHUB

    String workspaceName  // optional — used only on first sign-up
) {}

