package com.eakp.common.security;

/**
 * Optional interface for checking JWT blacklist status.
 * Downstream services (chat, ingestion, admin) implement this using their
 * Redis connection to verify tokens haven't been revoked via logout.
 *
 * <p>If no bean implementing this interface is registered, the
 * {@link JwtTokenValidator} will skip the blacklist check (fail-open).</p>
 */
public interface TokenBlacklistChecker {

    /**
     * Check if a token with the given JTI (JWT ID) has been blacklisted.
     *
     * @param jti the JWT ID ("jti" claim)
     * @return true if the token is blacklisted (revoked), false otherwise
     */
    boolean isBlacklisted(String jti);
}

