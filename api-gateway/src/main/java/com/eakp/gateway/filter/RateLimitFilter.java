package com.eakp.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${app.rate-limit.api-requests-per-minute:100}")
    private int apiRequestsPerMinute;

    @Value("${app.rate-limit.auth-requests-per-minute:10}")
    private int authRequestsPerMinute;

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, BucketEntry> buckets =
            new ConcurrentHashMap<>();

    private static final int MAX_BUCKETS = 10_000;
    private static final long EVICTION_INTERVAL_MINUTES = 5;

    private ScheduledExecutorService evictionExecutor;

    @PostConstruct
    void startEvictionScheduler() {
        evictionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-evictor");
            t.setDaemon(true);
            return t;
        });
        evictionExecutor.scheduleAtFixedRate(this::evictStale,
                EVICTION_INTERVAL_MINUTES, EVICTION_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopEvictionScheduler() {
        if (evictionExecutor != null) {
            evictionExecutor.shutdownNow();
        }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest  request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain         filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        boolean isAuthPath = path.startsWith("/api/v1/auth/");

        String key    = resolveKey(request);
        int    limit  = isAuthPath ? authRequestsPerMinute : apiRequestsPerMinute;
        Bucket bucket = buckets.computeIfAbsent(key,
                k -> new BucketEntry(newBucket(limit))).bucket();

        // Update last-accessed timestamp
        BucketEntry entry = buckets.get(key);
        if (entry != null) entry.touch();

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(request, response);
        } else {
            long retryAfterSeconds =
                    probe.getNanosToWaitForRefill() / 1_000_000_000;

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.addHeader("X-Rate-Limit-Retry-After-Seconds",
                    String.valueOf(retryAfterSeconds));

            log.warn("Rate limit exceeded for key: {}", key);
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "error",   "Too many requests",
                    "retryAfterSeconds", retryAfterSeconds
            ));
        }
    }

    private String resolveKey(HttpServletRequest request) {
        // Use userId if authenticated, otherwise IP
        Authentication auth =
                SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return "user:" + auth.getName();
        }
        return "ip:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Bucket newBucket(int requestsPerMinute) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip rate limiting for health checks and metrics
        return path.startsWith("/actuator/");
    }

    // ── Eviction ──────────────────────────────────────────────────────────────

    private void evictStale() {
        if (buckets.size() <= MAX_BUCKETS) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10);
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessed < cutoff);
        log.info("Rate limit eviction: {} → {} entries", before, buckets.size());
    }

    private static class BucketEntry {
        final Bucket bucket;
        volatile long lastAccessed;

        BucketEntry(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccessed = System.currentTimeMillis();
        }

        Bucket bucket() { return bucket; }
        void touch() { lastAccessed = System.currentTimeMillis(); }
    }
}
