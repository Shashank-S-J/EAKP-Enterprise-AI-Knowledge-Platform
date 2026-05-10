package com.eakp.ingestion.config;

import com.eakp.common.security.BaseJwtAuthFilter;
import com.eakp.common.security.JwtTokenValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Ingestion-service JWT filter — delegates to shared BaseJwtAuthFilter.
 */
@Component
public class IngestionJwtFilter extends BaseJwtAuthFilter {

    public IngestionJwtFilter(JwtTokenValidator jwtValidator, ObjectMapper objectMapper) {
        super(jwtValidator, objectMapper);
    }

    @Override
    protected String getServiceName() {
        return "ingestion-service";
    }
}
