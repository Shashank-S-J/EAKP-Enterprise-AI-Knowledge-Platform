package com.eakp.ingestion.config;

import com.eakp.common.security.TokenBlacklistChecker;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed JWT blacklist checker.
 * Checks the same Redis keys used by api-gateway's logout flow.
 */
@Component
public class RedisTokenBlacklistChecker implements TokenBlacklistChecker {

    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";
    private final StringRedisTemplate redis;

    public RedisTokenBlacklistChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }
}

