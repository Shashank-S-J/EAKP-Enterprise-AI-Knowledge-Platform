package com.eakp.chat.service;

import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Custom Micrometer metrics for the RAG system.
 * All metrics are exposed via /actuator/prometheus and scraped by Grafana.
 *
 * Metrics:
 *   rag.cache.hits / rag.cache.misses       → cache effectiveness
 *   rag.pipeline.latency                    → end-to-end response time
 *   rag.faithfulness.score                  → hallucination guard scores
 *   rag.hallucinations.detected             → ungrounded answers count
 *   rag.tokens.used                         → LLM token consumption
 *   rag.chunks.retrieved                    → avg chunks per query
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiMetricsService {

    private final MeterRegistry registry;

    private Counter  cacheHits;
    private Counter  cacheMisses;
    private Counter  hallucinationsDetected;
    private Counter  ragQueries;
    private Timer    ragPipelineLatency;
    private Timer    llmLatency;
    private DistributionSummary faithfulnessScores;
    private DistributionSummary chunksRetrieved;
    private DistributionSummary tokensUsed;

    @PostConstruct
    void init() {
        cacheHits = Counter.builder("rag.cache.hits")
                .description("Semantic cache hits")
                .tag("type", "semantic")
                .register(registry);

        cacheMisses = Counter.builder("rag.cache.misses")
                .description("Semantic cache misses")
                .register(registry);

        hallucinationsDetected = Counter.builder("rag.hallucinations.detected")
                .description("Answers flagged as ungrounded")
                .register(registry);

        ragQueries = Counter.builder("rag.queries.total")
                .description("Total RAG queries processed")
                .register(registry);

        ragPipelineLatency = Timer.builder("rag.pipeline.latency")
                .description("Full RAG pipeline latency (retrieve + rerank + generate)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        llmLatency = Timer.builder("rag.llm.latency")
                .description("LLM generation latency only")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        faithfulnessScores = DistributionSummary.builder("rag.faithfulness.score")
                .description("Grounding check confidence scores (0-100)")
                .scale(100)
                .publishPercentiles(0.5, 0.95)
                .register(registry);

        chunksRetrieved = DistributionSummary.builder("rag.chunks.retrieved")
                .description("Number of chunks retrieved per query")
                .register(registry);

        tokensUsed = DistributionSummary.builder("rag.tokens.used")
                .description("Estimated tokens per LLM call")
                .register(registry);
    }

    // ── Recording methods ─────────────────────────────────────────────────────

    public void recordCacheHit() {
        cacheHits.increment();
        ragQueries.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
        ragQueries.increment();
    }

    public void recordPipelineLatency(Duration d) {
        ragPipelineLatency.record(d);
    }

    public void recordLlmLatency(Duration d) {
        llmLatency.record(d);
    }

    public void recordFaithfulness(double score) {
        faithfulnessScores.record(score);
        if (score < 0.5) {
            hallucinationsDetected.increment();
            log.warn("Low faithfulness score detected: {}", score);
        }
    }

    public void recordChunksRetrieved(int count) {
        chunksRetrieved.record(count);
    }

    public void recordTokensUsed(int tokens) {
        tokensUsed.record(tokens);
    }

    // ── Gauge helpers (call once at startup or periodically) ──────────────────

    public void registerCacheSize(java.util.function.Supplier<Number> supplier) {
        Gauge.builder("rag.cache.size", supplier, s -> s.get().doubleValue())
                .description("Current number of entries in semantic cache")
                .register(registry);
    }
}
