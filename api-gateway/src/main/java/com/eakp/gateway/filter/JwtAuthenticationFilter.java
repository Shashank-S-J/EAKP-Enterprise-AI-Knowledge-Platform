package com.eakp.gateway.filter;

import com.eakp.gateway.service.EakpUserDetailsService;
import com.eakp.gateway.service.JwtTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService         jwtService;
    private final EakpUserDetailsService  userDetailsService;
    private final ObjectMapper            objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // No Bearer token — skip (SecurityConfig will deny if endpoint is protected)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        try {
            final String email = jwtService.extractEmail(jwt);

            // Only process if not already authenticated
            if (email != null &&
                    SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails =
                        userDetailsService.loadUserByUsername(email);

                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // Attach workspaceId as the "credentials" field
                    // — accessible anywhere via SecurityContextHolder
                    UUID workspaceId = jwtService.extractWorkspaceId(jwt);

                    // Store userId as request attribute for downstream
                    Claims claims = jwtService.extractAllClaims(jwt);
                    String userIdStr = claims.get("userId", String.class);
                    if (userIdStr != null) {
                        request.setAttribute("userId", UUID.fromString(userIdStr));
                    }

                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    workspaceId,           // ← workspaceId here
                                    userDetails.getAuthorities());

                    authToken.setDetails(
                            new WebAuthenticationDetailsSource()
                                    .buildDetails(request));

                    SecurityContextHolder.getContext()
                            .setAuthentication(authToken);

                    log.debug("Authenticated user: {} workspace: {}",
                            email, workspaceId);
                }
            }
        } catch (JwtException e) {
            log.warn("JWT validation error: {}", e.getMessage());
            sendError(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "Invalid or expired token");
            return;
        } catch (Exception e) {
            log.error("Authentication error", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Authentication error");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void sendError(HttpServletResponse response,
                           int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                Map.of("error", message, "status", status));
    }
}
