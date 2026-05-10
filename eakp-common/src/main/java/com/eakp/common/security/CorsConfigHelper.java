package com.eakp.common.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;
public class CorsConfigHelper {
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOriginsConfig;
    @Value("${app.cors.allowed-headers:Authorization,Content-Type,X-Correlation-Id}")
    private String allowedHeadersConfig;
    @Value("${app.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS}")
    private String allowedMethodsConfig;
    @Value("${app.cors.max-age:3600}")
    private long maxAge;
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOriginsConfig.split(",")));
        config.setAllowedMethods(Arrays.asList(allowedMethodsConfig.split(",")));
        config.setAllowedHeaders(Arrays.asList(allowedHeadersConfig.split(",")));
        config.setAllowCredentials(true);
        config.setMaxAge(maxAge);
        config.setExposedHeaders(List.of("X-Correlation-Id"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}