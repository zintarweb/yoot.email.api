package com.emailutilities.controller;

import com.emailutilities.entity.EmailAccount;
import com.emailutilities.entity.User;
import com.emailutilities.repository.EmailAccountRepository;
import com.emailutilities.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

    private final EmailAccountRepository emailAccountRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${oauth.google.client-id:}")
    private String googleClientId;

    @Value("${oauth.google.client-secret:}")
    private String googleClientSecret;

    @Value("${oauth.google.redirect-uri:}")
    private String googleRedirectUri;

    @Value("${oauth.microsoft.client-id:}")
    private String microsoftClientId;

    @Value("${oauth.microsoft.client-secret:}")
    private String microsoftClientSecret;

    @Value("${oauth.microsoft.redirect-uri:}")
    private String microsoftRedirectUri;

    public OAuthController(EmailAccountRepository emailAccountRepository, UserRepository userRepository) {
        this.emailAccountRepository = emailAccountRepository;
        this.userRepository = userRepository;
    }

    /**
     * Initiate OAuth flow - returns URL to redirect user to
     */
    @GetMapping("/authorize/{provider}")
    public ResponseEntity<?> authorize(
            @PathVariable String provider,
            @RequestParam(required = false) String login_hint,
            @RequestParam(required = false) Long userId) {

        // Default to user 1 if not specified (backward compatibility)
        Long effectiveUserId = userId != null ? userId : 1L;

        // Verify user exists
        if (!userRepository.existsById(effectiveUserId)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "User not found",
                "userId", effectiveUserId
            ));
        }

        String authUrl;
        // Encode userId in state for retrieval in callback
        String state = effectiveUserId + ":" + UUID.randomUUID().toString();

        switch (provider.toLowerCase()) {
            case "google", "gmail" -> {
                if (googleClientId == null || googleClientId.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Google OAuth not configured",
                        "message", "Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET environment variables"
                    ));
                }
                authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                    "client_id=" + encode(googleClientId) +
                    "&redirect_uri=" + encode(googleRedirectUri) +
                    "&response_type=code" +
                    "&scope=" + encode("https://mail.google.com/ profile email") +
                    "&access_type=offline" +
                    "&prompt=consent" +
                    "&state=" + state;
                // Add login_hint to pre-select the account
                if (login_hint != null && !login_hint.isEmpty()) {
                    authUrl += "&login_hint=" + encode(login_hint);
                }
            }
            case "microsoft", "outlook" -> {
                if (microsoftClientId == null || microsoftClientId.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "Microsoft OAuth not configured",
                        "message", "Set MICROSOFT_CLIENT_ID and MICROSOFT_CLIENT_SECRET environment variables"
                    ));
                }
                // Use select_account for new accounts, consent for reconnecting
                String promptType = (login_hint != null && !login_hint.isEmpty()) ? "consent" : "select_account";
                authUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?" +
                    "client_id=" + encode(microsoftClientId) +
                    "&redirect_uri=" + encode(microsoftRedirectUri) +
                    "&response_type=code" +
                    "&scope=" + encode("https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/Mail.Send https://graph.microsoft.com/User.Read offline_access openid profile email") +
                    "&prompt=" + promptType +
                    "&state=" + state;
                // Add login_hint to pre-select the account for reconnecting
                if (login_hint != null && !login_hint.isEmpty()) {
                    authUrl += "&login_hint=" + encode(login_hint);
                }
            }
            default -> {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown provider: " + provider
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
            "authUrl", authUrl,
            "state", state
        ));
    }

    /**
     * OAuth callback - exchanges code for tokens
     */
    @GetMapping("/callback/{provider}")
    public ResponseEntity<String> callback(
            @PathVariable String provider,
            @RequestParam String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {

        if (error != null) {
            return ResponseEntity.ok(generateCallbackHtml(false, "Authorization denied: " + error, null));
        }

        try {
            Map<String, Object> tokenResponse;
            String email;
            EmailAccount.EmailProvider emailProvider;

            switch (provider.toLowerCase()) {
                case "google" -> {
                    tokenResponse = exchangeGoogleCode(code);
                    email = getGoogleUserEmail((String) tokenResponse.get("access_token"));
                    emailProvider = EmailAccount.EmailProvider.GMAIL;
                }
                case "microsoft" -> {
                    tokenResponse = exchangeMicrosoftCode(code);
                    email = getMicrosoftUserEmail((String) tokenResponse.get("access_token"));
                    emailProvider = EmailAccount.EmailProvider.OUTLOOK;
                }
                default -> {
                    return ResponseEntity.ok(generateCallbackHtml(false, "Unknown provider", null));
                }
            }

            // Extract userId from state (format: "userId:uuid")
            Long parsedUserId = 1L; // Default
            if (state != null && state.contains(":")) {
                try {
                    parsedUserId = Long.parseLong(state.split(":")[0]);
                } catch (NumberFormatException e) {
                    // Use default
                }
            }
            final Long userId = parsedUserId;

            User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

            // Calculate token expiry time
            Integer expiresIn = (Integer) tokenResponse.get("expires_in");
            java.time.LocalDateTime tokenExpiresAt = expiresIn != null
                ? java.time.LocalDateTime.now().plusSeconds(expiresIn)
                : java.time.LocalDateTime.now().plusHours(1); // Default 1 hour

            // Check if account already exists for this user (reconnecting)
            EmailAccount account = emailAccountRepository.findByUserIdAndEmailAddressAndProvider(user.getId(), email, emailProvider)
                .orElse(new EmailAccount());

            account.setUser(user);
            account.setEmailAddress(email);
            account.setProvider(emailProvider);
            account.setAccessToken((String) tokenResponse.get("access_token"));
            account.setRefreshToken((String) tokenResponse.get("refresh_token"));
            account.setTokenExpiresAt(tokenExpiresAt);
            account.setSyncStatus(EmailAccount.SyncStatus.SYNCED);
            account.setLastSyncError(null);

            emailAccountRepository.save(account);

            return ResponseEntity.ok(generateCallbackHtml(true, "Account connected: " + email, email));

        } catch (Exception e) {
            return ResponseEntity.ok(generateCallbackHtml(false, "Error: " + e.getMessage(), null));
        }
    }

    private Map<String, Object> exchangeGoogleCode(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", googleClientId);
        body.add("client_secret", googleClientSecret);
        body.add("redirect_uri", googleRedirectUri);
        body.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
            "https://oauth2.googleapis.com/token",
            request,
            Map.class
        );

        return response;
    }

    private Map<String, Object> exchangeMicrosoftCode(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", microsoftClientId);
        body.add("client_secret", microsoftClientSecret);
        body.add("redirect_uri", microsoftRedirectUri);
        body.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            request,
            Map.class
        );

        return response;
    }

    private String getGoogleUserEmail(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        @SuppressWarnings("unchecked")
        Map<String, Object> userInfo = restTemplate.exchange(
            "https://www.googleapis.com/oauth2/v2/userinfo",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        ).getBody();

        return (String) userInfo.get("email");
    }

    private String getMicrosoftUserEmail(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        @SuppressWarnings("unchecked")
        Map<String, Object> userInfo = restTemplate.exchange(
            "https://graph.microsoft.com/v1.0/me",
            org.springframework.http.HttpMethod.GET,
            new HttpEntity<>(headers),
            Map.class
        ).getBody();

        // For personal accounts, 'mail' might be null, use userPrincipalName as fallback
        String email = (String) userInfo.get("mail");
        if (email == null || email.isEmpty()) {
            email = (String) userInfo.get("userPrincipalName");
        }
        return email;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String generateCallbackHtml(boolean success, String message, String email) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Email Utilities - OAuth</title>
                <style>
                    body { font-family: sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background: #f8fafc; }
                    .card { background: white; padding: 40px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); text-align: center; max-width: 400px; }
                    .success { color: #22c55e; }
                    .error { color: #ef4444; }
                    h2 { margin-bottom: 10px; }
                    p { color: #64748b; }
                    .close-btn { margin-top: 20px; padding: 10px 20px; background: #2563eb; color: white; border: none; border-radius: 6px; cursor: pointer; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2 class="%s">%s</h2>
                    <p>%s</p>
                    <button class="close-btn" onclick="window.close(); window.opener && window.opener.location.reload();">Close</button>
                </div>
                <script>
                    if (window.opener) {
                        window.opener.postMessage({ type: 'oauth-complete', success: %s, email: '%s' }, '*');
                    }
                </script>
            </body>
            </html>
            """.formatted(
                success ? "success" : "error",
                success ? "Success!" : "Failed",
                message,
                success,
                email != null ? email : ""
            );
    }
}
