package com.eakp.chat.config;

import com.eakp.chat.service.SemanticCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically purges expired semantic cache entries from pgvector.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CachePurgeScheduler {

    private final SemanticCacheService cacheService;

    @Scheduled(fixedRateString = "${app.rag.cache-purge-interval-ms:3600000}") // every hour
    public void purgeExpiredCacheEntries() {
        int purged = cacheService.purgeExpired();
        if (purged > 0) {
            log.info("Scheduled cache purge removed {} expired entries", purged);
        }
    }
}

