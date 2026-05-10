package com.eakp.gateway.service;

import com.eakp.gateway.dto.AuthResponse;
import com.eakp.gateway.dto.LoginRequest;
import com.eakp.gateway.dto.OAuthLoginRequest;
import com.eakp.gateway.dto.RegisterRequest;
import com.eakp.gateway.exception.AuthException;
import com.eakp.common.model.EakpRole;
import com.eakp.gateway.model.User;
import com.eakp.gateway.model.Workspace;
import com.eakp.gateway.repository.UserRepository;
import com.eakp.gateway.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository        userRepository;
    private final WorkspaceRepository   workspaceRepository;
    private final JwtTokenService       jwtTokenService;
    private final PasswordEncoder       passwordEncoder;
    private final AuthenticationManager authManager;
    private final LoginAttemptService   loginAttemptService;

    // ── Register ──────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new AuthException("Email already in use: " + request.email());
        }

        // Create or find workspace
        Workspace workspace = workspaceRepository
                .findBySlug(request.workspaceSlug())
                .orElseGet(() -> workspaceRepository.save(
                        Workspace.builder()
                                .name(request.workspaceName())
                                .slug(request.workspaceSlug())
                                .build()));

        // Enforce password complexity
        validatePasswordComplexity(request.password());

        User user = userRepository.save(User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .role(EakpRole.USER.name())
                .authProvider("LOCAL")
                .workspaceId(workspace.getId())
                .active(true)
                .build());

        log.info("New user registered: {} in workspace: {}",
                user.getEmail(), workspace.getSlug());

        return buildAuthResponse(user);
    }

    // ── OAuth Login or Register ──────────────────────────────────────────

    @Transactional
    public AuthResponse oauthLoginOrRegister(OAuthLoginRequest request) {
        String provider = request.provider().toUpperCase();

        // Check if user already exists
        return userRepository.findByEmail(request.email())
                .map(existingUser -> {
                    // User exists — check if active, then log them in
                    if (!existingUser.isActive()) {
                        throw new AuthException("Account is deactivated. Contact your administrator.");
                    }
                    log.info("OAuth login for existing user: {} via {}", existingUser.getEmail(), provider);
                    return buildAuthResponse(existingUser);
                })
                .orElseGet(() -> {
                    // New user — create workspace + user
                    String wsName = request.workspaceName() != null && !request.workspaceName().isBlank()
                            ? request.workspaceName()
                            : request.fullName() + "'s Workspace";
                    String wsSlug = wsName.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                            .replaceAll("^-|-$", "");

                    Workspace workspace = workspaceRepository
                            .findBySlug(wsSlug)
                            .orElseGet(() -> workspaceRepository.save(
                                    Workspace.builder()
                                            .name(wsName)
                                            .slug(wsSlug)
                                            .build()));

                    User user = userRepository.save(User.builder()
                            .email(request.email())
                            .password(null)  // OAuth users have no password
                            .fullName(request.fullName())
                            .role(EakpRole.USER.name())
                            .authProvider(provider)
                            .workspaceId(workspace.getId())
                            .active(true)
                            .build());

                    log.info("New OAuth user registered: {} via {} in workspace: {}",
                            user.getEmail(), provider, workspace.getSlug());
                    return buildAuthResponse(user);
                });
    }

    // ── Login ─────────────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest request) {
     // Check brute-force lockout
        if (loginAttemptService.isLocked(request.email())) {
            long remaining = loginAttemptService.getRemainingLockoutSeconds(request.email());
            throw new AuthException(
                    "Account temporarily locked due to too many failed attempts. Try again in "
                    + (remaining / 60 + 1) + " minutes.");
        }

        // Check if user exists and is active before attempting authentication
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    loginAttemptService.recordFailure(request.email());
                    return new BadCredentialsException("Invalid email or password");
                });

        // Reject OAuth-only users trying to login with password
        if (user.getPassword() == null) {
            throw new AuthException(
                    "This account uses " + user.getAuthProvider() + " login. Please sign in with " +
                    user.getAuthProvider().toLowerCase() + " instead.");
        }

        if (!user.isActive()) {
            throw new AuthException("Account is deactivated. Contact your administrator.");
        }

        try {
            authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.email(), request.password()));
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(request.email());
            throw new BadCredentialsException("Invalid email or password");
        }

        // Clear failed attempts on success
        loginAttemptService.clearAttempts(request.email());
        log.info("User logged in: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    public AuthResponse refresh(String refreshToken) {
        if (jwtTokenService.isTokenExpired(refreshToken)) {
            throw new AuthException("Refresh token expired");
        }

        // Verify this is actually a refresh token, not an access token
        String tokenType = jwtTokenService.extractAllClaims(refreshToken)
                .get("tokenType", String.class);
        if (!"REFRESH".equals(tokenType)) {
            throw new AuthException("Invalid token type: expected REFRESH token");
        }

        String email = jwtTokenService.extractEmail(refreshToken);
        User   user  = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("User not found"));

        // Blacklist old refresh token
        jwtTokenService.blacklist(refreshToken);

        log.info("Tokens refreshed for: {}", email);
        return buildAuthResponse(user);
    }

    // ── Logout ────────────────────────────────────────────────────────────

    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                jwtTokenService.blacklist(accessToken);
            } catch (Exception e) {
                log.warn("Could not blacklist access token: {}", e.getMessage());
            }
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                jwtTokenService.blacklist(refreshToken);
            } catch (Exception e) {
                log.warn("Could not blacklist refresh token: {}", e.getMessage());
            }
        }
        log.info("User logged out, tokens blacklisted");
    }

    // ── Helper ────────────────────────────────────────────────────────────

    @Transactional
    public void updateProfile(java.util.UUID userId, String fullName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));
        user.setFullName(fullName);
        userRepository.save(user);
        log.info("Profile updated for user: {}", user.getEmail());
    }

    @Transactional
    public void changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthException("User not found"));

        // Validate new password complexity
        validatePasswordComplexity(newPassword);

        // OAuth user setting password for the first time
        if (user.getPassword() == null) {
            // Allow setting a password without current password verification
            log.info("OAuth user {} setting password for the first time", user.getEmail());
        } else {
            // Existing password user — verify current password
            if (currentPassword == null || currentPassword.isBlank()) {
                throw new AuthException("Current password is required");
            }
            if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
                throw new AuthException("Current password is incorrect");
            }
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password changed for user: {}", user.getEmail());
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken  = jwtTokenService.generateAccessToken(user);
        String refreshToken = jwtTokenService.generateRefreshToken(user);

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenService.extractExpiry(accessToken).getTime(),
                user.getId().toString(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getWorkspaceId().toString()
        );
    }

    /**
     * Request a password reset. Generates a time-limited token and stores in Redis.
     * In production, this would send an email. For now, logs the token.
     */
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String resetToken = java.util.UUID.randomUUID().toString();
            // Store token in Redis with 15-minute TTL
            jwtTokenService.storePasswordResetToken(resetToken, user.getEmail());
            log.info("Password reset requested for: {}", email);
            // TODO: Send email with reset link containing the token
            // In dev mode, log a masked version for debugging
            log.debug("Reset token (dev): {}...{}", resetToken.substring(0, 4), resetToken.substring(resetToken.length() - 4));
        });
    }

    /**
     * Reset password using a valid reset token from Redis.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        validatePasswordComplexity(newPassword);
        String email = jwtTokenService.validatePasswordResetToken(token);
        if (email == null) {
            throw new AuthException("Invalid or expired reset token");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new AuthException("User not found"));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        jwtTokenService.invalidatePasswordResetToken(token);
        log.info("Password reset completed for: {}", email);
    }

    // ── Password Complexity ────────────────────────────────────────────

    /**
     * Enforce enterprise-grade password complexity:
     *   - 8–128 characters
     *   - At least one uppercase letter
     *   - At least one lowercase letter
     *   - At least one digit
     *   - At least one special character
     */
    private void validatePasswordComplexity(String password) {
        if (password == null || password.length() < 8) {
            throw new AuthException("Password must be at least 8 characters");
        }
        if (password.length() > 128) {
            throw new AuthException("Password must not exceed 128 characters");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new AuthException("Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[a-z].*")) {
            throw new AuthException("Password must contain at least one lowercase letter");
        }
        if (!password.matches(".*[0-9].*")) {
            throw new AuthException("Password must contain at least one digit");
        }
        if (!password.matches(".*[^A-Za-z0-9].*")) {
            throw new AuthException("Password must contain at least one special character");
        }
    }
}
