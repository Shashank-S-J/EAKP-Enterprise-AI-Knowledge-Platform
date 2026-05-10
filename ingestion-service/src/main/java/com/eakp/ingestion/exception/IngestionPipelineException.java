package com.eakp.ingestion.exception;

/**
 * Thrown when the document ingestion pipeline fails at any stage.
 */
public class IngestionPipelineException extends RuntimeException {

    public IngestionPipelineException(String message, Throwable cause) {
        super(message, cause);
    }

    public IngestionPipelineException(String message) {
        super(message);
    }
}

