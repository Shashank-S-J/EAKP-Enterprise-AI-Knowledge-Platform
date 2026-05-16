package com.eakp.chat.service;

import com.eakp.chat.service.RagPipelineService.RetrievedChunk;
import com.eakp.chat.service.RagPipelineService.ScoredChunk;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Re-ranks retrieved chunks using a cross-encoder approach.
 *
 * Production option: Cohere Rerank API (free tier: 1000 calls/month)
 * Dev/free option:   LLM-based scoring (slower but zero cost)
 *
 * Toggle via app.rag.reranker property:
 *   "llm"    → LLM scores each chunk (default, fully free)
 *   "cohere" → Cohere rerank API (better quality)
 *   "none"   → pass-through (use RRF order as-is)
 */
@Service
@Slf4j
public class ReRankingService {

    private final ChatClient guardClient;

    public ReRankingService(@Qualifier("guardChatClient") ChatClient guardClient) {
        this.guardClient = guardClient;
    }

    @Value("${app.rag.reranker:llm}")
    private String rerankerType;

    @Value("${app.rag.top-k-rerank:5}")
    private int topKRerank;

    @CircuitBreaker(name = "llmRerank", fallbackMethod = "rerankFallback")
    @Timed(value = "rag.rerank.latency", description = "Re-ranking latency")
    public List<RetrievedChunk> rerank(String query,
                                       List<ScoredChunk> candidates) {
        if (candidates.isEmpty()) return List.of();

        return switch (rerankerType.toLowerCase()) {
            case "none"   -> passthroughRerank(candidates);
            case "llm"    -> llmRerank(query, candidates);
            default       -> {
                log.warn("Unknown reranker '{}', using passthrough", rerankerType);
                yield passthroughRerank(candidates);
            }
        };
    }

    // ── Circuit Breaker Fallback ─────────────────────────────────────────────

    @SuppressWarnings("unused")
    private List<RetrievedChunk> rerankFallback(String query,
                                                List<ScoredChunk> candidates,
                                                Throwable t) {
        log.warn("Rerank circuit breaker triggered: {} — using passthrough", t.getMessage());
        return passthroughRerank(candidates);
    }

    // ── Passthrough (RRF order preserved) ────────────────────────────────────

    private List<RetrievedChunk> passthroughRerank(List<ScoredChunk> chunks) {
        List<RetrievedChunk> result = new ArrayList<>();
        for (int i = 0; i < Math.min(chunks.size(), topKRerank); i++) {
            ScoredChunk c = chunks.get(i);
            result.add(new RetrievedChunk(
                    c.id(), c.content(), c.source(), c.documentId(),
                    1.0 - (i * 0.1)   // descending synthetic score
            ));
        }
        return result;
    }

    // ── LLM-based re-ranking (free, no external API) ─────────────────────────

    /**
     * Ask the LLM to score each chunk's relevance to the query (0-10).
     * Runs candidate chunks in a single batched prompt for efficiency.
     */
    private List<RetrievedChunk> llmRerank(String query,
                                           List<ScoredChunk> candidates) {
        // Always rerank, even small lists. RRF ordering is noisy — skipping
        // rerank for "small" lists used to ship whichever chunk happened to
        // score high on BM25, which is often wrong for paraphrased queries.

        StringBuilder prompt = new StringBuilder();
        prompt.append("Score each passage's relevance to the query.\n");
        prompt.append("Respond ONLY with a JSON array of scores (0-10).\n");
        prompt.append("Example: [8, 3, 9, 1, 7]\n\n");
        prompt.append("Query: ").append(query).append("\n\n");

        for (int i = 0; i < candidates.size(); i++) {
            prompt.append("Passage ").append(i).append(":\n");
            // Show the FIRST 600 chars + LAST 200 chars of each chunk.
            // 200-char snippets hide evidence past the lead paragraph; a
            // head+tail window is the standard fix for "lost in the middle"
            // failures inside the reranker itself.
            String snippet = buildSnippet(candidates.get(i).content(), 600, 200);
            prompt.append(snippet).append("\n\n");
        }

        try {
            String raw = guardClient.prompt()
                    .user(prompt.toString())
                    .call()
                    .content();

            if (raw == null || raw.isBlank()) {
                log.warn("LLM returned null/empty for re-ranking, using passthrough");
                return passthroughRerank(candidates);
            }

            List<Double> scores = parseScores(raw, candidates.size());

            // Pair candidates with scores and sort descending
            record Scored(ScoredChunk chunk, double score) {}
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                scored.add(new Scored(candidates.get(i), scores.get(i)));
            }
            scored.sort((a, b) -> Double.compare(b.score(), a.score()));

            List<RetrievedChunk> result = new ArrayList<>();
            for (int i = 0; i < Math.min(topKRerank, scored.size()); i++) {
                Scored s = scored.get(i);
                result.add(new RetrievedChunk(
                        s.chunk().id(),
                        s.chunk().content(),
                        s.chunk().source(),
                        s.chunk().documentId(),
                        s.score() / 10.0
                ));
            }
            log.debug("LLM re-ranking complete, top score: {}",
                    result.isEmpty() ? 0 : result.get(0).relevanceScore());
            return result;

        } catch (Exception e) {
            log.warn("LLM re-ranking failed, falling back to passthrough: {}",
                    e.getMessage());
            return passthroughRerank(candidates);
        }
    }

    /**
     * Head + tail snippet. For short chunks returns the chunk as-is; for
     * long chunks returns first {@code head} chars + {@code tail} chars
     * joined by an ellipsis marker so the reranker can see both ends.
     */
    private static String buildSnippet(String content, int head, int tail) {
        if (content == null) return "";
        if (content.length() <= head + tail) return content;
        return content.substring(0, head)
                + "\n[…]\n"
                + content.substring(content.length() - tail);
    }

    private List<Double> parseScores(String raw, int expectedSize) {
        // Extract JSON array from raw LLM response
        int start = raw.indexOf('[');
        int end   = raw.lastIndexOf(']');
        if (start == -1 || end == -1) {
            return defaultScores(expectedSize);
        }
        String json = raw.substring(start, end + 1);
        json = json.replaceAll("[^0-9.,\\[\\]]", "");
        String[] parts = json.replaceAll("[\\[\\]]", "").split(",");

        List<Double> scores = new ArrayList<>();
        for (String part : parts) {
            try {
                scores.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException e) {
                scores.add(5.0);   // neutral default
            }
        }

        // Pad or trim to expected size
        while (scores.size() < expectedSize) scores.add(5.0);
        return scores.subList(0, expectedSize);
    }

    private List<Double> defaultScores(int size) {
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < size; i++) scores.add((double)(size - i));
        return scores;
    }
}