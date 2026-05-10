package com.eakp.ingestion.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses any document format into plain text using Apache Tika.
 *
 * Supported: PDF, DOCX, DOC, PPTX, XLSX, TXT, MD, HTML, CSV, ODT, RTF, …
 *
 * Apache Tika auto-detects the format and applies the correct parser.
 * We use the BodyContentHandler to extract only text (no markup).
 */
@Component
@Slf4j
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * Parse a document stream into plain text + metadata.
     *
     * @param inputStream Raw bytes of the document
     * @param filename    Original filename (helps Tika detect format)
     * @return ParsedDocument containing extracted text and metadata map
     */
    public ParsedDocument parse(InputStream inputStream,
                                 String filename) throws ParsingException {
        try {
            // Unlimited content handler (-1 = no character limit)
            BodyContentHandler handler  = new BodyContentHandler(-1);
            Metadata            tikaMeta = new Metadata();
            ParseContext        context  = new ParseContext();

            tikaMeta.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);

            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, tikaMeta, context);

            String text = handler.toString().trim();
            if (text.isEmpty()) {
                throw new ParsingException("No text could be extracted from: " + filename);
            }

            // Collect Tika metadata into a map
            Map<String, Object> meta = new HashMap<>();
            for (String name : tikaMeta.names()) {
                meta.put(name, tikaMeta.get(name));
            }
            meta.put("source",   filename);
            meta.put("charCount", text.length());

            log.info("Parsed '{}' → {} chars, {} metadata fields",
                    filename, text.length(), meta.size());

            return new ParsedDocument(text, meta, detectFileType(filename));

        } catch (IOException | SAXException | TikaException e) {
            throw new ParsingException("Failed to parse document: " + filename, e);
        }
    }

    private String detectFileType(String filename) {
        if (filename == null) return "unknown";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "pdf";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "docx";
        if (lower.endsWith(".pptx")) return "pptx";
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return "xlsx";
        if (lower.endsWith(".txt"))  return "txt";
        if (lower.endsWith(".md"))   return "md";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".csv"))  return "csv";
        return "unknown";
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    public record ParsedDocument(
        String              text,
        Map<String, Object> metadata,
        String              fileType
    ) {}

    public static class ParsingException extends Exception {
        public ParsingException(String msg)            { super(msg); }
        public ParsingException(String msg, Throwable c) { super(msg, c); }
    }
}
