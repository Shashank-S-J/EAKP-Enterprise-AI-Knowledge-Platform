package com.eakp.ingestion.pipeline;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts text chunks into dense vector embeddings using Spring AI's
 * EmbeddingModel abstraction.
 *
 * Provider-agnostic: works with Ollama (local), OpenAI, HuggingFace.
 * The model is configured entirely via application.yml.
 *
 * Batching: Embedding APIs have rate limits and max-token-per-request
 * constraints. We batch chunks in groups of `batchSize` to avoid errors
 * and to maximise throughput.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;
    private final MeterRegistry  meterRegistry;

    @Value("${app.ingestion.batch-size:50}")
    private int batchSize;

    /**
     * Configured vector dimension. Must match the pgvector column.
     * Wrong-dimension fallback vectors silently corrupt the index and are
     * the root cause of "search returns nothing" bugs that look like a
     * retrieval problem.
     */
    @Value("${spring.ai.vectorstore.pgvector.dimensions:1024}")
    private int embeddingDimensions;

    private Timer embeddingTimer;

    @PostConstruct
    void init() {
        embeddingTimer = Timer.builder("ingestion.embedding.latency")
                .description("Time to embed a batch of chunks")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);
    }

    /**
     * Embed a list of text chunks in batches.
     * Returns one float[] per chunk, in the same order.
     */
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> allEmbeddings = new ArrayList<>(texts.size());

        // Process in batches to respect API limits
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end   = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            log.debug("Embedding batch {}/{}: {} texts",
                    (i / batchSize) + 1,
                    (int) Math.ceil((double) texts.size() / batchSize),
                    batch.size());

            List<float[]> batchEmbeddings = embeddingTimer.record(
                    () -> embedSingleBatch(batch));

            allEmbeddings.addAll(batchEmbeddings);
        }

        log.info("Embedded {} chunks total", allEmbeddings.size());
        return allEmbeddings;
    }

    /** Embed a single text (convenience wrapper). */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<float[]> embedSingleBatch(List<String> texts) {
        try {
            EmbeddingRequest  request  = new EmbeddingRequest(texts, null);
            EmbeddingResponse response = embeddingModel.call(request);

            List<float[]> out = response.getResults().stream()
                    .map(r -> r.getOutput())
                    .toList();
            // Defensive: a wrong-dim vector inserted into pgvector either
            // throws at write-time or silently kills similarity scores.
            // Validate upfront so the error is loud and recoverable.
            for (float[] v : out) {
                if (v == null || v.length != embeddingDimensions) {
                    throw new IllegalStateException(
                            "Embedding dimension mismatch: expected "
                                    + embeddingDimensions + " got "
                                    + (v == null ? "null" : v.length));
                }
            }
            return out;
        } catch (Exception e) {
            log.error("Embedding batch failed, falling back to individual: {}",
                    e.getMessage());
            // Fall back to individual embedding on batch failure
            return texts.stream()
                    .map(this::embedSafe)
                    .toList();
        }
    }

    private float[] embedSafe(String text) {
        try {
            float[] v = embeddingModel.embed(text);
            if (v == null || v.length != embeddingDimensions) {
                throw new IllegalStateException(
                        "Embedding dimension mismatch: expected "
                                + embeddingDimensions + " got "
                                + (v == null ? "null" : v.length));
            }
            return v;
        } catch (Exception e) {
            log.error("Failed to embed text snippet, returning zero vector: {}",
                    e.getMessage());
            // Zero vector at the CONFIGURED dim — never hardcoded. A 768-dim
            // fallback into a 1024-dim store used to throw at write-time or
            // corrupt similarity scores.
            return new float[embeddingDimensions];
        }
    }
}