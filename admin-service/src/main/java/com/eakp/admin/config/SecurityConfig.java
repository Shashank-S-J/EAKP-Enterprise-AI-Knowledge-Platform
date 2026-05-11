package com.eakp.admin.config;

import com.eakp.common.security.BaseJwtAuthFilter;
import com.eakp.common.security.CorsConfigHelper;
import com.eakp.common.security.JwtTokenValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Import(CorsConfigHelper.class)
public class SecurityConfig {

    private final AdminJwtFilter jwtFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/prometheus", "/error").permitAll()
                        // Admin endpoints: ADMIN role required
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // Analytics readable by any authenticated user
                        .requestMatchers("/api/v1/analytics/**").authenticated()
                        // Workspace config readable by any authenticated user
                        .requestMatchers("/api/v1/workspaces/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }


    // ── JWT filter ────────────────────────────────────────────────────────────

    @Component
    static class AdminJwtFilter extends BaseJwtAuthFilter {
        AdminJwtFilter(JwtTokenValidator jwtValidator, ObjectMapper om) {
            super(jwtValidator, om);
        }

        @Override
        protected String getServiceName() {
            return "admin-service";
        }
    }
}