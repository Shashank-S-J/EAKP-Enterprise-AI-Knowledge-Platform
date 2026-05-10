package com.eakp.chat.config;

import com.eakp.common.security.BaseJwtAuthFilter;
import com.eakp.common.security.JwtTokenValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Chat-service JWT filter — delegates to shared BaseJwtAuthFilter.
 */
@Component
public class JwtAuthFilter extends BaseJwtAuthFilter {

    public JwtAuthFilter(JwtTokenValidator jwtValidator, ObjectMapper objectMapper) {
        super(jwtValidator, objectMapper);
    }

    @Override
    protected String getServiceName() {
        return "chat-service";
    }
}
