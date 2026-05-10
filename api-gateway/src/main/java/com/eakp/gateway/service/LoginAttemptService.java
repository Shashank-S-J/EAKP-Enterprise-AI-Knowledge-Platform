package com.eakp.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Tracks failed login attempts per email using Redis.
 * Locks out an account for 15 minutes after 5 consecutive failures.
 * Prevents brute-force password guessing attacks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "login:attempts:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Record a failed login attempt.
     */
    public void recordFailure(String email) {
        String key = KEY_PREFIX + email.toLowerCase();
        Long attempts = redisTemplate.opsForValue().increment(key);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(key, LOCKOUT_DURATION);
        }
        log.warn("Failed login attempt #{} for: {}", attempts, email);
    }

    /**
     * Check if the account is currently locked out.
     */
    public boolean isLocked(String email) {
        String key = KEY_PREFIX + email.toLowerCase();
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) return false;
        try {
            return Integer.parseInt(val) >= MAX_ATTEMPTS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Clear failed attempts on successful login.
     */
    public void clearAttempts(String email) {
        redisTemplate.delete(KEY_PREFIX + email.toLowerCase());
    }

    /**
     * Get remaining lockout time in seconds (0 if not locked).
     */
    public long getRemainingLockoutSeconds(String email) {
        String key = KEY_PREFIX + email.toLowerCase();
        Long ttl = redisTemplate.getExpire(key);
        return (ttl != null && ttl > 0) ? ttl : 0;
    }
}


