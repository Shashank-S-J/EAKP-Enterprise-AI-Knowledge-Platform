package com.eakp.gateway.service;

import com.eakp.gateway.exception.AuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Verifies Google ID tokens server-side via Google's tokeninfo endpoint.
 * This prevents the client from forging OAuth claims.
 *
 * <p><b>Production Security Notes:</b></p>
 * <ul>
 *   <li>Google's tokeninfo endpoint validates the token signature, expiry, and issuer.</li>
 *   <li>We additionally verify the audience (aud) matches our client ID to prevent
 *       tokens issued for other applications from being accepted.</li>
 *   <li>We verify email_verified=true to ensure the Google account email is confirmed.</li>
 *   <li>For higher-throughput production deployments, consider using Google's official
 *       Java client library (google-api-client) for local JWT verification without
 *       a network call to Google on every authentication.</li>
 * </ul>
 */
@Service
@Slf4j
public class GoogleOAuthVerifier {

    @Value("${app.google.client-id:}")
    private String expectedClientId;

    private final RestTemplate restTemplate = new RestTemplate();

    public record GoogleUser(String email, String name) {}

    /**
     * Verify a Google ID token by calling Google's tokeninfo endpoint.
     * Validates: signature, expiry, audience (client_id), and email verification.
     *
     * @param idToken the raw Google ID token from the client
     * @return a verified GoogleUser with email and name
     * @throws AuthException if verification fails for any reason
     */
    @SuppressWarnings("unchecked")
    public GoogleUser verifyIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            throw new AuthException("Google ID token is required");
        }

        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    "https://oauth2.googleapis.com/tokeninfo?id_token=" + idToken,
                    Map.class
            );

            Map<String, Object> payload = response.getBody();
            if (payload == null) {
                throw new AuthException("Empty response from Google token verification");
            }

            // Check for error
            if (payload.containsKey("error")) {
                throw new AuthException("Google token verification failed: " + payload.get("error_description"));
            }

            // Verify audience matches our client ID — prevents token substitution attacks
            String aud = (String) payload.get("aud");
            if (expectedClientId == null || expectedClientId.isBlank()) {
                log.warn("⚠️  Google client-id not configured (app.google.client-id). "
                        + "Audience validation is DISABLED. Set GOOGLE_CLIENT_ID for production!");
            } else if (!expectedClientId.equals(aud)) {
                log.warn("Google token audience mismatch: expected={}, got={}", expectedClientId, aud);
                throw new AuthException("Invalid Google token: audience mismatch");
            }

            // Verify issuer — must be accounts.google.com or https://accounts.google.com
            String iss = (String) payload.get("iss");
            if (iss != null && !iss.equals("accounts.google.com") && !iss.equals("https://accounts.google.com")) {
                log.warn("Google token issuer mismatch: {}", iss);
                throw new AuthException("Invalid Google token: issuer mismatch");
            }

            // Verify email is verified
            String emailVerified = String.valueOf(payload.get("email_verified"));
            if (!"true".equals(emailVerified)) {
                throw new AuthException("Google account email is not verified");
            }

            String email = (String) payload.get("email");
            String name = (String) payload.get("name");
            if (email == null || email.isBlank()) {
                throw new AuthException("Could not retrieve email from Google token");
            }
            if (name == null || name.isBlank()) {
                name = email.split("@")[0]; // fallback
            }

            log.info("Google token verified for: {}", email);
            return new GoogleUser(email, name);

        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google token verification failed", e);
            throw new AuthException("Failed to verify Google ID token");
        }
    }
}
