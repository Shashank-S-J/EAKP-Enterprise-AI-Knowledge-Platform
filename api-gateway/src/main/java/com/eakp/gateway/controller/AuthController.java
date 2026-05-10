package com.eakp.gateway.controller;

import com.eakp.gateway.dto.*;
import com.eakp.gateway.model.User;
import com.eakp.gateway.service.AuthService;
import com.eakp.gateway.service.GitHubOAuthService;
import com.eakp.gateway.service.GoogleOAuthVerifier;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final GitHubOAuthService gitHubOAuthService;
    private final GoogleOAuthVerifier googleOAuthVerifier;

    /**
     * POST /api/v1/auth/register
     * Create a new user and workspace.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /**
     * POST /api/v1/auth/oauth
     * Login or register via OAuth provider (Google/GitHub).
     * If user exists, logs them in. If not, creates a new account.
     */
    @PostMapping("/oauth")
    public AuthResponse oauthLogin(@Valid @RequestBody com.eakp.gateway.dto.OAuthLoginRequest request) {
        return authService.oauthLoginOrRegister(request);
    }

    /**
     * POST /api/v1/auth/login
     * Authenticate and get access + refresh tokens.
     */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * POST /api/v1/auth/refresh
     * Exchange a valid refresh token for a new token pair.
     */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * POST /api/v1/auth/logout
     * Blacklist the current access token (and optionally the refresh token).
     * Requires a valid Bearer token in Authorization header.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) LogoutRequest request) {

        String accessToken = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;
        String refreshToken = request != null ? request.refreshToken() : null;
        authService.logout(accessToken, refreshToken);

        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * POST /api/v1/auth/oauth/github/callback
     * Exchange a GitHub authorization code for user info, then login or register.
     */
    @PostMapping("/oauth/github/callback")
    public AuthResponse githubCallback(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        String workspaceName = body.get("workspaceName");
        if (code == null || code.isBlank()) {
            throw new com.eakp.gateway.exception.AuthException("GitHub authorization code is required");
        }
        var ghUser = gitHubOAuthService.exchangeCodeForUser(code);
        OAuthLoginRequest oauthReq = new OAuthLoginRequest(
                ghUser.email(), ghUser.name(), "GITHUB", workspaceName);
        return authService.oauthLoginOrRegister(oauthReq);
    }

    /**
     * POST /api/v1/auth/oauth/google/callback
     * Verify a Google ID token server-side via Google's tokeninfo endpoint,
     * then login or register.
     */
    @PostMapping("/oauth/google/callback")
    public AuthResponse googleCallback(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        String workspaceName = body.get("workspaceName");
        if (idToken == null || idToken.isBlank()) {
            throw new com.eakp.gateway.exception.AuthException("Google ID token is required");
        }
        var googleUser = googleOAuthVerifier.verifyIdToken(idToken);
        OAuthLoginRequest oauthReq = new OAuthLoginRequest(
                googleUser.email(), googleUser.name(), "GOOGLE", workspaceName);
        return authService.oauthLoginOrRegister(oauthReq);
    }

    /**
     * GET /api/v1/auth/me
     * Return the currently authenticated user's profile.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(Map.of(
            "id",           user.getId().toString(),
            "email",        user.getEmail(),
            "fullName",     user.getFullName() != null ? user.getFullName() : "",
            "role",         user.getRole(),
            "authProvider",  user.getAuthProvider(),
            "workspaceId",  user.getWorkspaceId().toString()
        ));
    }

    /**
     * PATCH /api/v1/auth/profile
     * Update the authenticated user's profile (fullName, etc.).
     */
    @PatchMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> body) {
        String newFullName = body.get("fullName");
        if (newFullName != null) {
            authService.updateProfile(user.getId(), newFullName);
        }
        return ResponseEntity.ok(Map.of("message", "Profile updated"));
    }

    /**
     * POST /api/v1/auth/change-password
     * Change the authenticated user's password.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> body) {
        authService.changePassword(user.getId(), body.get("currentPassword"), body.get("newPassword"));
        return ResponseEntity.ok(Map.of("message", "Password changed"));
    }

    /**
     * POST /api/v1/auth/forgot-password
     * Request a password reset email. Always returns 200 to prevent email enumeration.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email != null && !email.isBlank()) {
            authService.requestPasswordReset(email);
        }
        // Always return success to prevent email enumeration attacks
        return ResponseEntity.ok(Map.of("message", "If an account exists, a reset link has been sent."));
    }

    /**
     * POST /api/v1/auth/reset-password
     * Reset password using a valid reset token.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        String newPassword = body.get("newPassword");
        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token and new password are required"));
        }
        authService.resetPassword(token, newPassword);
        return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
    }
}
