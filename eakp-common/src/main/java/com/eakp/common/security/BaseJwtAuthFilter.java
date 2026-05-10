package com.eakp.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared JWT authentication filter for downstream services (chat, ingestion, admin).
 * Extracts JWT from Authorization header, validates it, and sets the SecurityContext.
 *
 * <p>Subclasses can override {@link #getServiceName()} for log messages.</p>
 */
public abstract class BaseJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BaseJwtAuthFilter.class);

    private final JwtTokenValidator jwtValidator;
    private final ObjectMapper objectMapper;

    protected BaseJwtAuthFilter(JwtTokenValidator jwtValidator, ObjectMapper objectMapper) {
        this.jwtValidator = jwtValidator;
        this.objectMapper = objectMapper;
    }

    /** Override to customise the service name in log messages. */
    protected abstract String getServiceName();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            if (jwtValidator.isValid(token)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                String email       = jwtValidator.extractEmail(token);
                String role        = jwtValidator.extractRole(token);
                UUID   workspaceId = jwtValidator.extractWorkspaceId(token);
                UUID   userId      = jwtValidator.extractUserId(token);

                // Store userId in request attribute for controllers
                if (userId != null) {
                    request.setAttribute("userId", userId);
                }

                var auth = new UsernamePasswordAuthenticationToken(
                        email,
                        workspaceId,   // credentials = workspaceId (consistent pattern)
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                log.debug("{} authenticated: {} ws:{}", getServiceName(), email, workspaceId);
            }
        } catch (JwtException e) {
            log.warn("JWT error in {}: {}", getServiceName(), e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(),
                    Map.of("error", "Invalid or expired token", "status", 401));
            return;
        }

        chain.doFilter(request, response);
    }
}

