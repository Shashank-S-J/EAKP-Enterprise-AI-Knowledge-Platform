package com.eakp.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email")
    String email,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
    String password,

    @NotBlank(message = "Full name is required")
    String fullName,

    @NotBlank(message = "Workspace name is required")
    String workspaceName,

    @NotBlank(message = "Workspace slug is required")
    @Size(min = 2, max = 50, message = "Slug must be 2-50 characters")
    @Pattern(regexp = "^[a-z0-9-]+$",
             message = "Slug must be lowercase letters, numbers, hyphens only")
    String workspaceSlug
) {}
