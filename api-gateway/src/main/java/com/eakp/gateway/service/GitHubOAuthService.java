package com.eakp.gateway.service;

import com.eakp.gateway.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Exchanges a GitHub OAuth authorization code for user profile information.
 */
@Service
@Slf4j
public class GitHubOAuthService {

    @Value("${app.github.client-id:}")
    private String clientId;

    @Value("${app.github.client-secret:}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    public record GitHubUser(String email, String name) {}

    /**
     * Exchange GitHub authorization code for access token, then fetch user profile.
     */
    public GitHubUser exchangeCodeForUser(String code) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new AuthException("GitHub OAuth is not configured on the server");
        }

        // 1. Exchange code for access token
        String accessToken = exchangeCodeForToken(code);

        // 2. Fetch user profile
        return fetchGitHubUser(accessToken);
    }

    private String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code
        );

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://github.com/login/oauth/access_token",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            Map<String, Object> responseBody = response.getBody();
            if (responseBody == null || responseBody.containsKey("error")) {
                String error = responseBody != null ? (String) responseBody.get("error_description") : "Unknown error";
                throw new AuthException("GitHub OAuth failed: " + error);
            }

            String token = (String) responseBody.get("access_token");
            if (token == null || token.isBlank()) {
                throw new AuthException("No access token received from GitHub");
            }
            return token;
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("GitHub token exchange failed", e);
            throw new AuthException("Failed to exchange GitHub authorization code");
        }
    }

    @SuppressWarnings("unchecked")
    private GitHubUser fetchGitHubUser(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            // Fetch profile
            ResponseEntity<Map> profileResp = restTemplate.exchange(
                    "https://api.github.com/user",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Map<String, Object> profile = profileResp.getBody();
            String name = (String) profile.getOrDefault("name", profile.getOrDefault("login", "GitHub User"));
            String email = (String) profile.get("email");

            // If email is private, fetch from /user/emails
            if (email == null || email.isBlank()) {
                ResponseEntity<List> emailsResp = restTemplate.exchange(
                        "https://api.github.com/user/emails",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        List.class
                );
                List<Map<String, Object>> emails = emailsResp.getBody();
                if (emails != null) {
                    email = emails.stream()
                            .filter(e -> Boolean.TRUE.equals(e.get("primary")))
                            .map(e -> (String) e.get("email"))
                            .findFirst()
                            .orElse(emails.stream()
                                    .filter(e -> Boolean.TRUE.equals(e.get("verified")))
                                    .map(e -> (String) e.get("email"))
                                    .findFirst()
                                    .orElse(null));
                }
            }

            if (email == null || email.isBlank()) {
                throw new AuthException("Could not retrieve email from GitHub. Please make your email public in GitHub settings.");
            }

            return new GitHubUser(email, name);
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("GitHub user fetch failed", e);
            throw new AuthException("Failed to fetch GitHub user profile");
        }
    }
}

