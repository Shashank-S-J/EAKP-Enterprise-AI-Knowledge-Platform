package com.eakp.chat.config;

import com.eakp.chat.service.SemanticCacheService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically purges expired semantic cache entries from pgvector.
 * Also wipes the entire cache once at startup to clear any
 * "no documents" answers cached during pipeline failures (these
 * otherwise persist for 24h via TTL and keep returning stale text
 * even after documents are successfully ingested).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CachePurgeScheduler {

    private final SemanticCacheService cacheService;
    private final JdbcTemplate         jdbcTemplate;

    @PostConstruct
    void clearStaleCacheOnStartup() {
        try {
            int wiped = jdbcTemplate.update("DELETE FROM semantic_cache");
            if (wiped > 0) {
                log.info("Startup cache wipe: removed {} entries (possibly poisoned)", wiped);
            }
        } catch (Exception e) {
            log.warn("Startup cache wipe failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRateString = "${app.rag.cache-purge-interval-ms:3600000}") // every hour
    public void purgeExpiredCacheEntries() {
        int purged = cacheService.purgeExpired();
        if (purged > 0) {
            log.info("Scheduled cache purge removed {} expired entries", purged);
        }
    }
}