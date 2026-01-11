package com.emailutilities.service;

import com.emailutilities.entity.EmailAccount;
import com.emailutilities.repository.EmailAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class GmailService {

    private final EmailAccountRepository emailAccountRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String GMAIL_API_BASE = "https://gmail.googleapis.com/gmail/v1/users/me";

    @Value("${oauth.google.client-id:}")
    private String googleClientId;

    @Value("${oauth.google.client-secret:}")
    private String googleClientSecret;

    public GmailService(EmailAccountRepository emailAccountRepository) {
        this.emailAccountRepository = emailAccountRepository;
    }

    /**
     * Refresh access token using refresh token
     */
    private String refreshAccessToken(EmailAccount account) {
        String refreshToken = account.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new RuntimeException("No refresh token available. Please re-authenticate.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", googleClientId);
        body.add("client_secret", googleClientSecret);
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                "https://oauth2.googleapis.com/token",
                request,
                Map.class
            );

            if (response != null && response.containsKey("access_token")) {
                String newAccessToken = (String) response.get("access_token");
                account.setAccessToken(newAccessToken);

                // Update token expiry time
                Integer expiresIn = (Integer) response.get("expires_in");
                if (expiresIn != null) {
                    account.setTokenExpiresAt(java.time.LocalDateTime.now().plusSeconds(expiresIn));
                } else {
                    account.setTokenExpiresAt(java.time.LocalDateTime.now().plusHours(1));
                }

                account.setSyncStatus(EmailAccount.SyncStatus.SYNCED);
                account.setLastSyncError(null);
                emailAccountRepository.save(account);
                System.out.println("Successfully refreshed access token for: " + account.getEmailAddress());
                return newAccessToken;
            }
            throw new RuntimeException("Failed to refresh token - no access_token in response");
        } catch (Exception e) {
            System.err.println("Token refresh failed: " + e.getMessage());
            throw new RuntimeException("Failed to refresh token: " + e.getMessage());
        }
    }

    /**
     * Fetch emails from Gmail inbox with pagination
     */
    public Map<String, Object> fetchInbox(Long accountId, int maxResults) {
        return fetchInbox(accountId, maxResults, null, null, null);
    }

    public Map<String, Object> fetchInbox(Long accountId, int maxResults, String pageToken) {
        return fetchInbox(accountId, maxResults, pageToken, null, null);
    }

    /**
     * Fetch ALL emails (not just inbox) for analytics sync
     */
    public Map<String, Object> fetchAllEmails(Long accountId, int maxResults, String pageToken) {
        return fetchEmailsInternal(accountId, maxResults, pageToken, null, null, false);
    }

    /**
     * Fetch emails with date range filtering for multi-account pagination
     * @param before ISO date string (exclusive) - fetch emails before this date
     * @param after ISO date string (inclusive) - fetch emails after this date
     */
    public Map<String, Object> fetchInbox(Long accountId, int maxResults, String pageToken, String before, String after) {
        return fetchEmailsInternal(accountId, maxResults, pageToken, before, after, true);
    }

    private Map<String, Object> fetchEmailsInternal(Long accountId, int maxResults, String pageToken, String before, String after, boolean inboxOnly) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        if (account.getProvider() != EmailAccount.EmailProvider.GMAIL) {
            throw new RuntimeException("Account is not a Gmail account");
        }

        String accessToken = account.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            throw new RuntimeException("No access token available");
        }

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            System.out.println("Token expiring soon, proactively refreshing...");
            accessToken = refreshAccessToken(account);
        }

        // Try to fetch, refresh token on 401, retry once
        return fetchEmailsWithToken(account, accessToken, maxResults, pageToken, before, after, inboxOnly, true);
    }

    private Map<String, Object> fetchEmailsWithToken(EmailAccount account, String accessToken, int maxResults, String pageToken, String before, String after, boolean inboxOnly, boolean allowRetry) {
        try {
            // Get list of message IDs
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String listUrl = GMAIL_API_BASE + "/messages?maxResults=" + maxResults;
            if (inboxOnly) {
                listUrl += "&labelIds=INBOX";
            }
            if (pageToken != null && !pageToken.isEmpty()) {
                listUrl += "&pageToken=" + pageToken;
            }

            // Build date query for Gmail (uses epoch seconds)
            StringBuilder query = new StringBuilder();
            if (before != null && !before.isEmpty()) {
                try {
                    long epochSeconds = java.time.Instant.parse(before).getEpochSecond();
                    query.append("before:").append(epochSeconds).append(" ");
                } catch (Exception e) {
                    System.err.println("Invalid before date: " + before);
                }
            }
            if (after != null && !after.isEmpty()) {
                try {
                    long epochSeconds = java.time.Instant.parse(after).getEpochSecond();
                    query.append("after:").append(epochSeconds).append(" ");
                } catch (Exception e) {
                    System.err.println("Invalid after date: " + after);
                }
            }
            if (query.length() > 0) {
                listUrl += "&q=" + java.net.URLEncoder.encode(query.toString().trim(), java.nio.charset.StandardCharsets.UTF_8);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> listResponse = restTemplate.exchange(
                listUrl,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (listResponse == null || !listResponse.containsKey("messages")) {
                return Map.of("emails", List.of(), "total", 0);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> messageRefs = (List<Map<String, String>>) listResponse.get("messages");

            // Fetch details for each message
            List<Map<String, Object>> emails = new ArrayList<>();
            for (Map<String, String> msgRef : messageRefs) {
                String messageId = msgRef.get("id");
                Map<String, Object> emailDetails = fetchEmailDetails(accessToken, messageId);
                if (emailDetails != null) {
                    emails.add(emailDetails);
                }
            }

            // Update sync status
            account.setSyncStatus(EmailAccount.SyncStatus.SYNCED);
            account.setLastSyncAt(java.time.LocalDateTime.now());
            emailAccountRepository.save(account);

            Map<String, Object> result = new HashMap<>();
            result.put("emails", emails);
            result.put("total", listResponse.getOrDefault("resultSizeEstimate", emails.size()));
            if (listResponse.containsKey("nextPageToken")) {
                result.put("nextPageToken", listResponse.get("nextPageToken"));
            }
            return result;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                // Try to refresh token and retry
                System.out.println("Access token expired, attempting refresh...");
                String newToken = refreshAccessToken(account);
                return fetchEmailsWithToken(account, newToken, maxResults, pageToken, before, after, inboxOnly, false);
            }
            account.setSyncStatus(EmailAccount.SyncStatus.ERROR);
            account.setLastSyncError("Token expired - need to re-authenticate");
            emailAccountRepository.save(account);
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        } catch (Exception e) {
            account.setSyncStatus(EmailAccount.SyncStatus.ERROR);
            account.setLastSyncError(e.getMessage());
            emailAccountRepository.save(account);
            throw new RuntimeException("Failed to fetch emails: " + e.getMessage());
        }
    }

    private Map<String, Object> fetchEmailDetails(String accessToken, String messageId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String detailUrl = GMAIL_API_BASE + "/messages/" + messageId + "?format=metadata&metadataHeaders=From&metadataHeaders=To&metadataHeaders=Subject&metadataHeaders=Date";

            @SuppressWarnings("unchecked")
            Map<String, Object> message = restTemplate.exchange(
                detailUrl,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (message == null) return null;

            // Extract headers
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) message.get("payload");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> headersData = (List<Map<String, String>>) payload.get("headers");

            String from = "";
            String to = "";
            String subject = "";
            String date = "";

            for (Map<String, String> header : headersData) {
                String name = header.get("name");
                String value = header.get("value");
                switch (name) {
                    case "From" -> from = value;
                    case "To" -> to = value;
                    case "Subject" -> subject = value;
                    case "Date" -> date = value;
                }
            }

            // Check labels for read/unread status
            @SuppressWarnings("unchecked")
            List<String> labelIds = (List<String>) message.get("labelIds");
            boolean isUnread = labelIds != null && labelIds.contains("UNREAD");

            return Map.of(
                "id", messageId,
                "from", from,
                "to", to,
                "subject", subject,
                "date", date,
                "snippet", message.getOrDefault("snippet", ""),
                "isUnread", isUnread,
                "threadId", message.getOrDefault("threadId", "")
            );

        } catch (Exception e) {
            System.err.println("Failed to fetch message " + messageId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Fetch a single email with full body
     */
    public Map<String, Object> fetchEmail(Long accountId, String messageId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        String url = GMAIL_API_BASE + "/messages/" + messageId + "?format=full";

        @SuppressWarnings("unchecked")
        Map<String, Object> message = restTemplate.exchange(
            url,
            HttpMethod.GET,
            entity,
            Map.class
        ).getBody();

        if (message == null) {
            throw new RuntimeException("Message not found");
        }

        // Extract headers and body
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.get("payload");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> headersData = (List<Map<String, String>>) payload.get("headers");

        String from = "", to = "", subject = "", date = "";
        for (Map<String, String> header : headersData) {
            String name = header.get("name");
            String value = header.get("value");
            switch (name) {
                case "From" -> from = value;
                case "To" -> to = value;
                case "Subject" -> subject = value;
                case "Date" -> date = value;
            }
        }

        // Get body content
        String body = extractBody(payload);

        return Map.of(
            "id", messageId,
            "from", from,
            "to", to,
            "subject", subject,
            "date", date,
            "body", body,
            "snippet", message.getOrDefault("snippet", "")
        );
    }

    private String extractBody(Map<String, Object> payload) {
        // Check for direct body
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.get("body");
        if (body != null && body.containsKey("data")) {
            return decodeBase64((String) body.get("data"));
        }

        // Check parts (multipart messages)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) payload.get("parts");
        if (parts != null) {
            for (Map<String, Object> part : parts) {
                String mimeType = (String) part.get("mimeType");
                if ("text/plain".equals(mimeType) || "text/html".equals(mimeType)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> partBody = (Map<String, Object>) part.get("body");
                    if (partBody != null && partBody.containsKey("data")) {
                        return decodeBase64((String) partBody.get("data"));
                    }
                }
                // Recursively check nested parts
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nestedParts = (List<Map<String, Object>>) part.get("parts");
                if (nestedParts != null) {
                    for (Map<String, Object> nestedPart : nestedParts) {
                        String nestedMimeType = (String) nestedPart.get("mimeType");
                        if ("text/plain".equals(nestedMimeType) || "text/html".equals(nestedMimeType)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> nestedBody = (Map<String, Object>) nestedPart.get("body");
                            if (nestedBody != null && nestedBody.containsKey("data")) {
                                return decodeBase64((String) nestedBody.get("data"));
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    private String decodeBase64(String data) {
        // Gmail uses URL-safe base64
        String base64 = data.replace('-', '+').replace('_', '/');
        byte[] decoded = Base64.getDecoder().decode(base64);
        return new String(decoded);
    }

    /**
     * Fetch all labels (folders) for a Gmail account
     */
    public List<Map<String, Object>> fetchLabels(Long accountId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();
        return fetchLabelsWithToken(account, accessToken, true);
    }

    private List<Map<String, Object>> fetchLabelsWithToken(EmailAccount account, String accessToken, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                GMAIL_API_BASE + "/labels",
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (response == null || !response.containsKey("labels")) {
                return List.of();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> labels = (List<Map<String, Object>>) response.get("labels");

            // Convert to standard format
            List<Map<String, Object>> folders = new ArrayList<>();
            for (Map<String, Object> label : labels) {
                String type = (String) label.getOrDefault("type", "user");
                String id = (String) label.get("id");
                String name = (String) label.get("name");

                // Include system labels and user labels
                folders.add(Map.of(
                    "id", id,
                    "name", name,
                    "type", type,
                    "messageCount", label.getOrDefault("messagesTotal", 0),
                    "unreadCount", label.getOrDefault("messagesUnread", 0)
                ));
            }
            return folders;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return fetchLabelsWithToken(account, newToken, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Fetch emails by label/folder
     */
    public Map<String, Object> fetchByLabel(Long accountId, String labelId, int maxResults) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();
        return fetchByLabelWithToken(account, accessToken, labelId, maxResults, true);
    }

    private Map<String, Object> fetchByLabelWithToken(EmailAccount account, String accessToken, String labelId, int maxResults, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String listUrl = GMAIL_API_BASE + "/messages?maxResults=" + maxResults + "&labelIds=" + labelId;

            @SuppressWarnings("unchecked")
            Map<String, Object> listResponse = restTemplate.exchange(
                listUrl,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (listResponse == null || !listResponse.containsKey("messages")) {
                return Map.of("emails", List.of(), "total", 0);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, String>> messageRefs = (List<Map<String, String>>) listResponse.get("messages");

            List<Map<String, Object>> emails = new ArrayList<>();
            for (Map<String, String> msgRef : messageRefs) {
                String messageId = msgRef.get("id");
                Map<String, Object> emailDetails = fetchEmailDetails(accessToken, messageId);
                if (emailDetails != null) {
                    emails.add(emailDetails);
                }
            }

            return Map.of(
                "emails", emails,
                "total", listResponse.getOrDefault("resultSizeEstimate", emails.size())
            );

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return fetchByLabelWithToken(account, newToken, labelId, maxResults, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Move email to a different label/folder
     */
    public void moveEmail(Long accountId, String messageId, String fromLabelId, String toLabelId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();
        moveEmailWithToken(account, accessToken, messageId, fromLabelId, toLabelId, true);
    }

    private void moveEmailWithToken(EmailAccount account, String accessToken, String messageId, String fromLabelId, String toLabelId, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = new HashMap<>();
            if (toLabelId != null && !toLabelId.isEmpty()) {
                body.put("addLabelIds", List.of(toLabelId));
            }
            if (fromLabelId != null && !fromLabelId.isEmpty()) {
                body.put("removeLabelIds", List.of(fromLabelId));
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForObject(
                GMAIL_API_BASE + "/messages/" + messageId + "/modify",
                entity,
                Map.class
            );

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                moveEmailWithToken(account, newToken, messageId, fromLabelId, toLabelId, false);
            } else {
                throw new RuntimeException("Access token expired. Please re-authenticate.");
            }
        }
    }

    /**
     * Create a new Gmail label
     */
    public String createLabel(Long accountId, String labelName) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "name", labelName,
            "labelListVisibility", "labelShow",
            "messageListVisibility", "show"
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
            GMAIL_API_BASE + "/labels",
            entity,
            Map.class
        );

        return (String) response.get("id");
    }

    /**
     * Get or create a label (idempotent)
     */
    public String getOrCreateLabel(Long accountId, String labelName) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            accessToken = refreshAccessToken(account);
        }

        return getOrCreateLabelWithToken(account, accessToken, labelName, true);
    }

    private String getOrCreateLabelWithToken(EmailAccount account, String accessToken, String labelName, boolean allowRetry) {
        try {
            // First, try to find existing label
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                GMAIL_API_BASE + "/labels",
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (response != null && response.containsKey("labels")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> labels = (List<Map<String, Object>>) response.get("labels");
                for (Map<String, Object> label : labels) {
                    if (labelName.equals(label.get("name"))) {
                        return (String) label.get("id");
                    }
                }
            }

            // Label doesn't exist, create it
            return createLabelWithToken(account, accessToken, labelName);

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return getOrCreateLabelWithToken(account, newToken, labelName, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    private String createLabelWithToken(EmailAccount account, String accessToken, String labelName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "name", labelName,
            "labelListVisibility", "labelShow",
            "messageListVisibility", "show"
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
            GMAIL_API_BASE + "/labels",
            entity,
            Map.class
        );

        return (String) response.get("id");
    }

    /**
     * Get folders (labels) in a format suitable for the bulk controller
     */
    public List<Map<String, Object>> getFolders(Long accountId) {
        return fetchLabels(accountId);
    }

    /**
     * Count emails from a sender in Gmail (used for debugging)
     */
    public int countEmailsFromSender(Long accountId, String senderEmail) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            accessToken = refreshAccessToken(account);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // First test: search all emails without filter
        String allUrl = GMAIL_API_BASE + "/messages?maxResults=1";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> allResponse = restTemplate.exchange(allUrl, HttpMethod.GET, entity, Map.class).getBody();
            System.out.println("[Gmail Debug] Total emails in mailbox: " + (allResponse != null ? allResponse.get("resultSizeEstimate") : "null"));
        } catch (Exception e) {
            System.err.println("[Gmail Debug] Total emails check failed: " + e.getMessage());
        }

        String query = "from:" + senderEmail;
        String url = GMAIL_API_BASE + "/messages?maxResults=1&q=" +
            java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);

        System.out.println("[Gmail Debug] Query: " + query);
        System.out.println("[Gmail Debug] URL: " + url);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();
            System.out.println("[Gmail Debug] Response: " + response);
            return response != null ? (int) response.getOrDefault("resultSizeEstimate", 0) : 0;
        } catch (Exception e) {
            System.err.println("[Gmail Debug] Failed to count emails from sender: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Move all emails from specified senders to a label (skips already-labeled emails)
     */
    public int moveEmailsBySenders(Long accountId, List<String> senderEmails, String toLabelId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        // First, get the label name to use in exclusion query
        String labelName = getLabelName(accessToken, toLabelId);
        int totalMoved = 0;

        for (String senderEmail : senderEmails) {
            String pageToken = null;
            int maxPages = 100; // Safety limit

            for (int page = 0; page < maxPages; page++) {
                // Search for emails from this sender, EXCLUDING those already with target label
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(accessToken);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                // Build query: from:sender -label:TargetLabel (exclude already-labeled)
                String query = "from:" + senderEmail;

                // On first page, check how many emails exist without exclusion (for logging)
                if (page == 0 && labelName != null) {
                    // Check emails from sender (without label exclusion)
                    String testUrl1 = GMAIL_API_BASE + "/messages?maxResults=1&q=" +
                        java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
                    // Check emails from sender WITH label (already segregated)
                    String alreadyLabeledQuery = query + " label:\"" + labelName + "\"";
                    String testUrl2 = GMAIL_API_BASE + "/messages?maxResults=1&q=" +
                        java.net.URLEncoder.encode(alreadyLabeledQuery, java.nio.charset.StandardCharsets.UTF_8);

                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resp1 = restTemplate.exchange(testUrl1, HttpMethod.GET, entity, Map.class).getBody();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> resp2 = restTemplate.exchange(testUrl2, HttpMethod.GET, entity, Map.class).getBody();

                        int totalEmails = resp1 != null ? (int) resp1.getOrDefault("resultSizeEstimate", 0) : 0;
                        int alreadyLabeled = resp2 != null ? (int) resp2.getOrDefault("resultSizeEstimate", 0) : 0;

                        System.out.println("[Gmail Segregate] Sender: " + senderEmail);
                        System.out.println("[Gmail Segregate] Total emails from sender: " + totalEmails);
                        System.out.println("[Gmail Segregate] Already have label '" + labelName + "': " + alreadyLabeled);
                        System.out.println("[Gmail Segregate] New emails to move: " + (totalEmails - alreadyLabeled));
                    } catch (Exception e) {
                        System.err.println("[Gmail Segregate] Check failed: " + e.getMessage());
                    }
                }

                // Now build the actual query with label exclusion
                if (labelName != null && !labelName.isEmpty()) {
                    // Quote the label name to handle slashes and spaces
                    query += " -label:\"" + labelName + "\"";
                }

                String url = GMAIL_API_BASE + "/messages?maxResults=100&q=" +
                    java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
                if (pageToken != null) {
                    url += "&pageToken=" + pageToken;
                }

                System.out.println("[Gmail] Search query: " + query);
                System.out.println("[Gmail] Encoded URL: " + url);

                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class
                ).getBody();

                System.out.println("[Gmail] Response: " + (response != null ? response.keySet() : "null"));
                if (response != null && response.containsKey("messages")) {
                    @SuppressWarnings("unchecked")
                    List<?> msgs = (List<?>) response.get("messages");
                    System.out.println("[Gmail] Found " + msgs.size() + " messages");
                } else {
                    System.out.println("[Gmail] No messages found for query");
                }

                if (response == null || !response.containsKey("messages")) {
                    break;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("messages");

                // Batch modify - add the target label
                if (!messages.isEmpty()) {
                    List<String> messageIds = new ArrayList<>();
                    for (Map<String, Object> msg : messages) {
                        messageIds.add((String) msg.get("id"));
                    }

                    HttpHeaders modifyHeaders = new HttpHeaders();
                    modifyHeaders.setBearerAuth(accessToken);
                    modifyHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

                    Map<String, Object> modifyBody = Map.of(
                        "ids", messageIds,
                        "addLabelIds", List.of(toLabelId)
                    );

                    HttpEntity<Map<String, Object>> modifyEntity = new HttpEntity<>(modifyBody, modifyHeaders);

                    restTemplate.postForObject(
                        GMAIL_API_BASE + "/messages/batchModify",
                        modifyEntity,
                        Void.class
                    );

                    totalMoved += messages.size();
                }

                pageToken = (String) response.get("nextPageToken");
                if (pageToken == null) {
                    break;
                }

                // Small delay to avoid rate limiting
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }

        return totalMoved;
    }

    /**
     * Get label name by ID (for query exclusion)
     */
    private String getLabelName(String accessToken, String labelId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> label = restTemplate.exchange(
                GMAIL_API_BASE + "/labels/" + labelId,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            return label != null ? (String) label.get("name") : null;
        } catch (Exception e) {
            return null;
        }
    }
}
