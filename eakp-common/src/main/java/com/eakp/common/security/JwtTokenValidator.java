package com.eakp.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

/**
 * Shared JWT token validator used by chat-service, ingestion-service, and admin-service.
 * Does NOT issue tokens — only validates them.
 * Must share the same {@code app.jwt.secret} as api-gateway.
 *
 * <p>If a {@link TokenBlacklistChecker} bean is available, revoked tokens
 * (blacklisted via logout) will also be rejected.</p>
 *
 * <p>In production, the JWT secret must be provided via the {@code JWT_SECRET} environment variable.
 * The default development secret will cause a startup failure in the {@code prod} profile.</p>
 */
@Component
public class JwtTokenValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

    private static final String DEV_SECRET =
            "dGhpcy1pcy1hLXZlcnktbG9uZy1zZWNyZXQta2V5LWZvci1kZXZlbG9wbWVudC1vbmx5LW1pbmltdW0tMjU2LWJpdHM=";

    @Value("${app.jwt.secret}")
    private String secret;

    private final Environment environment;
    private final TokenBlacklistChecker blacklistChecker;  // nullable

    public JwtTokenValidator(Environment environment,
                              @Autowired(required = false) TokenBlacklistChecker blacklistChecker) {
        this.environment = environment;
        this.blacklistChecker = blacklistChecker;
    }

    @PostConstruct
    void validateSecret() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 256 bits (32 bytes). Current: " + keyBytes.length + " bytes");
        }
        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (isProd && DEV_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "FATAL: Default development JWT secret detected in production! " +
                    "Set JWT_SECRET environment variable to a unique, cryptographically random base64-encoded key.");
        }
        if (DEV_SECRET.equals(secret)) {
            log.warn("⚠️  Using default development JWT secret. DO NOT use in production!");
        }
    }

    // ── Key ────────────────────────────────────────────────────────────────

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    // ── Claims extraction ──────────────────────────────────────────────────

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    public UUID extractWorkspaceId(String token) {
        String raw = extractAllClaims(token).get("workspaceId", String.class);
        if (raw == null) {
            throw new JwtException("Missing 'workspaceId' claim in JWT");
        }
        return UUID.fromString(raw);
    }

    public UUID extractUserId(String token) {
        String raw = extractAllClaims(token).get("userId", String.class);
        return raw != null ? UUID.fromString(raw) : null;
    }

    public String extractRole(String token) {
        return extractAllClaims(token).get("role", String.class);
    }

    public String extractTokenType(String token) {
        return extractAllClaims(token).get("tokenType", String.class);
    }

    // ── Validation ─────────────────────────────────────────────────────────

    public boolean isValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            if (claims.getExpiration().before(new Date())) {
                log.debug("JWT expired");
                return false;
            }
            // Reject refresh tokens used as access tokens
            String tokenType = claims.get("tokenType", String.class);
            if ("REFRESH".equals(tokenType)) {
                log.warn("Refresh token used as access token — rejected");
                return false;
            }
            // Check blacklist if available (tokens revoked via logout)
            if (blacklistChecker != null) {
                String jti = claims.getId();
                if (jti != null && blacklistChecker.isBlacklisted(jti)) {
                    log.info("Blacklisted JWT rejected: {}", jti);
                    return false;
                }
            }
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }
}

