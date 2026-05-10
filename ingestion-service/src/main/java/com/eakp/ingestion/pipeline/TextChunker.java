package com.eakp.ingestion.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a large document text into overlapping chunks suitable for embedding.
 *
 * Strategy: Recursive Character Splitter
 *   1. Try to split on paragraph boundaries (\n\n)
 *   2. Fall back to sentence boundaries (. ! ?)
 *   3. Fall back to word boundaries (space)
 *   4. Hard split as last resort
 *
 * Overlap: The last `overlap` characters of each chunk are repeated at
 * the start of the next chunk — preserving context across boundaries.
 *
 * Why not fixed-size splits?
 *   Fixed splits break sentences mid-thought, destroying semantic coherence.
 *   Recursive splitting respects natural language boundaries.
 */
@Component
@Slf4j
public class TextChunker {

    @Value("${app.ingestion.chunk-size:512}")
    private int chunkSize;

    @Value("${app.ingestion.chunk-overlap:64}")
    private int chunkOverlap;

    // Separators tried in order (highest to lowest priority)
    private static final String[] SEPARATORS = {
        "\n\n",    // paragraph break
        "\n",      // line break
        ". ",      // sentence end
        "! ",      // exclamation
        "? ",      // question
        "; ",      // semicolon
        ", ",      // comma
        " ",       // word boundary
        ""         // character boundary (last resort)
    };

    /**
     * Split text into a list of overlapping chunks.
     * Each chunk is at most `chunkSize` characters long.
     */
    public List<TextChunk> chunk(String text, String source) {
        List<String> rawChunks = splitRecursively(text.trim());
        List<TextChunk> chunks  = new ArrayList<>();

        for (int i = 0; i < rawChunks.size(); i++) {
            String content = rawChunks.get(i).trim();
            if (content.isEmpty()) continue;

            chunks.add(new TextChunk(
                content,
                i,
                source,
                estimateTokens(content)
            ));
        }

        log.debug("Chunked '{}': {} chars → {} chunks (size={}, overlap={})",
                source, text.length(), chunks.size(), chunkSize, chunkOverlap);

        return chunks;
    }

    // ── Core recursive split ──────────────────────────────────────────────────

    private List<String> splitRecursively(String text) {
        List<String> finalChunks = new ArrayList<>();
        splitWithSeparator(text, 0, finalChunks);
        return finalChunks;
    }

    private void splitWithSeparator(String text, int separatorIdx,
                                     List<String> result) {
        if (text.length() <= chunkSize) {
            result.add(text);
            return;
        }

        if (separatorIdx >= SEPARATORS.length) {
            // Hard split: character boundary
            hardSplit(text, result);
            return;
        }

        String separator = SEPARATORS[separatorIdx];
        String[] parts;

        if (separator.isEmpty()) {
            // Character-level split
            hardSplit(text, result);
            return;
        }

        parts = text.split(separator.equals("\n\n") ? "\\n\\n"
                : separator.equals("\n") ? "\\n"
                : java.util.regex.Pattern.quote(separator));

        if (parts.length <= 1) {
            // Separator not found — try next separator
            splitWithSeparator(text, separatorIdx + 1, result);
            return;
        }

        // Merge small parts into chunks with overlap
        mergeWithOverlap(parts, separator, separatorIdx, result);
    }

    private void mergeWithOverlap(String[] parts, String separator,
                                   int separatorIdx, List<String> result) {
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            String candidate = current.length() == 0
                    ? part
                    : current + separator + part;

            if (candidate.length() <= chunkSize) {
                if (current.length() > 0) current.append(separator);
                current.append(part);
            } else {
                // Flush current chunk
                if (current.length() > 0) {
                    String chunk = current.toString();
                    if (chunk.length() > chunkSize) {
                        // Chunk itself too big — recurse with next separator
                        splitWithSeparator(chunk, separatorIdx + 1, result);
                    } else {
                        result.add(chunk);
                    }
                }
                // Start new chunk with overlap from previous
                current = new StringBuilder();
                if (result.size() > 0) {
                    String prev    = result.get(result.size() - 1);
                    String overlap = prev.length() > chunkOverlap
                            ? prev.substring(prev.length() - chunkOverlap)
                            : prev;
                    current.append(overlap);
                    if (!current.isEmpty()) current.append(separator);
                }
                current.append(part);
            }
        }

        if (!current.isEmpty()) {
            String chunk = current.toString();
            if (chunk.length() > chunkSize) {
                splitWithSeparator(chunk, separatorIdx + 1, result);
            } else {
                result.add(chunk);
            }
        }
    }

    private void hardSplit(String text, List<String> result) {
        // Guard: overlap must be strictly less than chunkSize to guarantee progress
        int safeOverlap = Math.min(chunkOverlap, chunkSize - 1);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            result.add(text.substring(start, end));
            if (end == text.length()) break; // reached the end
            start = end - safeOverlap;
        }
    }

    /**
     * Rough token estimate: ~4 chars per token (GPT-style tokenisation).
     * Good enough for metadata; real count needs a tokenizer library.
     */
    private int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    // ── Value object ──────────────────────────────────────────────────────────

    public record TextChunk(
        String content,
        int    index,
        String source,
        int    estimatedTokens
    ) {}
}
