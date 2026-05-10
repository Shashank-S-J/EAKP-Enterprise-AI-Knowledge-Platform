package com.eakp.gateway.service;

import com.eakp.gateway.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

@Service
@RequiredArgsConstructor
@Slf4j
public class JwtTokenService {

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    private final StringRedisTemplate redis;
    private final Environment environment;

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private static final String DEV_SECRET = "dGhpcy1pcy1hLXZlcnktbG9uZy1zZWNyZXQta2V5LWZvci1kZXZlbG9wbWVudC1vbmx5LW1pbmltdW0tMjU2LWJpdHM=";

    @PostConstruct
    void validateSecret() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 256 bits (32 bytes). Current: " + keyBytes.length + " bytes");
        }
        boolean isProd = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
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
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // ── Token Generation ──────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId",      user.getId().toString());
        claims.put("workspaceId", user.getWorkspaceId().toString());
        claims.put("role",        user.getRole());
        claims.put("fullName",    user.getFullName());
        claims.put("tokenType",   "ACCESS");

        return buildToken(claims, user.getEmail(), expirationMs);
    }

    public String generateRefreshToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId",      user.getId().toString());
        claims.put("workspaceId", user.getWorkspaceId().toString());
        claims.put("tokenType",   "REFRESH");

        return buildToken(claims, user.getEmail(), refreshExpirationMs);
    }

    private String buildToken(Map<String, Object> claims,
                               String subject, long expMs) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expMs);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiry)
                .id(UUID.randomUUID().toString())   // jti - unique per token
                .signWith(signingKey())
                .compact();
    }

    // ── Token Parsing ─────────────────────────────────────────────────────

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
        return UUID.fromString(raw);
    }

    public String extractTokenId(String token) {
        return extractAllClaims(token).getId();
    }

    public Date extractExpiry(String token) {
        return extractAllClaims(token).getExpiration();
    }

    // ── Token Validation ──────────────────────────────────────────────────

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            String email    = extractEmail(token);
            String tokenId  = extractTokenId(token);

            boolean usernameMatch = email.equals(userDetails.getUsername());
            boolean notExpired    = !isTokenExpired(token);
            boolean notBlacklisted = !isBlacklisted(tokenId);

            return usernameMatch && notExpired && notBlacklisted;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isTokenExpired(String token) {
        return extractExpiry(token).before(new Date());
    }

    // ── Token Blacklisting (for logout) ───────────────────────────────────

    public void blacklist(String token) {
        try {
            String jti    = extractTokenId(token);
            Date   expiry = extractExpiry(token);
            long   ttl    = expiry.getTime() - System.currentTimeMillis();
            if (ttl > 0) {
                redis.opsForValue().set(
                        BLACKLIST_PREFIX + jti,
                        "revoked",
                        Duration.ofMillis(ttl));
                log.debug("Token blacklisted: {}", jti);
            }
        } catch (JwtException e) {
            log.warn("Could not blacklist token: {}", e.getMessage());
        }
    }

    public boolean isBlacklisted(String tokenId) {
        return Boolean.TRUE.equals(
                redis.hasKey(BLACKLIST_PREFIX + tokenId));
    }

    // ── Password Reset Token Management ────────────────────────────────────

    private static final String RESET_PREFIX = "pwd:reset:";
    private static final Duration RESET_TTL = Duration.ofMinutes(15);

    public void storePasswordResetToken(String token, String email) {
        redis.opsForValue().set(RESET_PREFIX + token, email, RESET_TTL);
    }

    public String validatePasswordResetToken(String token) {
        return redis.opsForValue().get(RESET_PREFIX + token);
    }

    public void invalidatePasswordResetToken(String token) {
        redis.delete(RESET_PREFIX + token);
    }
}
