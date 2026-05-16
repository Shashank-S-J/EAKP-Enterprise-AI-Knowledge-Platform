package com.eakp.chat.service;

import com.eakp.chat.service.RagPipelineService.RetrievedChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Validates that the LLM's answer is grounded in the retrieved context.
 *
 * Uses a secondary LLM call with a strict NLI-style prompt.
 * Returns a GroundingResult with:
 *   - grounded: true/false
 *   - confidence: 0.0 – 1.0
 *   - unsupportedClaims: list of sentences not supported by context
 *
 * If the check fails (LLM error, parse error), defaults to
 * grounded=true with low confidence rather than blocking the user.
 */
@Service
@Slf4j
public class HallucinationGuardService {

    private final ChatClient   guardClient;
    private final ObjectMapper objectMapper;

    public HallucinationGuardService(@Qualifier("guardChatClient") ChatClient guardClient,
                                     ObjectMapper objectMapper) {
        this.guardClient = guardClient;
        this.objectMapper = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    @CircuitBreaker(name = "llmGuard", fallbackMethod = "checkFallback")
    @Timed(value = "rag.guard.latency", description = "Hallucination guard check latency")
    public GroundingResult check(String answer,
                                 List<RetrievedChunk> context) {
        if (context.isEmpty()) {
            return new GroundingResult(false, 0.0,
                    List.of("No context was retrieved"));
        }

        String contextText = context.stream()
                .map(c -> "[" + c.source() + "]\n" + c.content())
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = """
            You are a strict fact-checker. Your task:
            Determine if every claim in the ANSWER is supported by the CONTEXT.

            Respond ONLY with valid JSON in this exact format:
            {
              "grounded": true,
              "confidence": 0.95,
              "unsupported_claims": []
            }

            Rules:
            - "grounded" = true only if ALL major factual claims are directly supported
            - "confidence" = 0.0 to 1.0 (your certainty in the grounding assessment)
            - "unsupported_claims" = list any specific sentences/claims not found in context
            - Opinions, hedging ("may", "might"), and meta-statements ("based on the docs")
              are NOT factual claims and should not be flagged
            - Numbers, dates, names, and technical details MUST be verified against context
            - If the answer says "I don't have enough information", set grounded=true, confidence=1.0

            CONTEXT:
            %s

            ANSWER:
            %s
            """.formatted(contextText, answer);

        try {
            String raw = guardClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseResult(raw);

        } catch (Exception e) {
            // Previously fail-open (grounded=true, conf=0.5). That let
            // unverified answers ship + cache. Now: be conservative — treat
            // a guard failure as "not verified" so the caller can append the
            // low-confidence disclaimer and skip caching. The user still
            // sees the streamed answer; only persistence/caching change.
            log.warn("Hallucination guard failed: {} — treating as low-confidence",
                    e.getMessage());
            return new GroundingResult(false, 0.0,
                    List.of("Guard check failed: " + e.getMessage()));
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Circuit breaker fallback. Conservative: treat unavailable guard as
     * unverified, not as "definitely grounded". The caller (ChatService) is
     * responsible for appending a disclaimer + skipping cache, not for
     * blocking the answer.
     */
    @SuppressWarnings("unused")
    private GroundingResult checkFallback(String answer,
                                          List<RetrievedChunk> context,
                                          Throwable t) {
        log.warn("Guard circuit breaker triggered: {} — treating as low-confidence", t.getMessage());
        return new GroundingResult(false, 0.0,
                List.of("Guard unavailable: " + t.getMessage()));
    }

    private GroundingResult parseResult(String raw) {
        try {
            // Strip markdown code fences if present
            String json = raw.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*\\n?", "").trim();
            }

            var node = objectMapper.readTree(json);

            boolean      grounded   = node.path("grounded").asBoolean(false);
            double       confidence = node.path("confidence").asDouble(0.0);
            List<String> claims     = objectMapper.convertValue(
                    node.path("unsupported_claims"),
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class));

            log.debug("Grounding check: grounded={} confidence={} claims={}",
                    grounded, confidence, claims.size());

            return new GroundingResult(grounded, confidence, claims);

        } catch (Exception e) {
            // Parse failure: conservative default. Previously this returned
            // grounded=true / conf=0.5 which silently bypassed every guard
            // downstream check. Now we surface it as low-confidence so the
            // disclaimer fires and caching is skipped.
            log.warn("Failed to parse grounding result: {}", e.getMessage());
            return new GroundingResult(false, 0.0, List.of());
        }
    }

    // ── Value Objects ─────────────────────────────────────────────────────────

    public record GroundingResult(
            boolean      grounded,
            double       confidence,
            List<String> unsupportedClaims
    ) {
        public boolean hasUnsupportedClaims() {
            return unsupportedClaims != null && !unsupportedClaims.isEmpty();
        }
    }
}