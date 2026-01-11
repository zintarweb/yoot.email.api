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
public class OutlookService {

    private final EmailAccountRepository emailAccountRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String GRAPH_API_BASE = "https://graph.microsoft.com/v1.0/me";
    private static final String GRAPH_API_BETA = "https://graph.microsoft.com/beta/me";

    @Value("${oauth.microsoft.client-id:}")
    private String microsoftClientId;

    @Value("${oauth.microsoft.client-secret:}")
    private String microsoftClientSecret;

    public OutlookService(EmailAccountRepository emailAccountRepository) {
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
        body.add("client_id", microsoftClientId);
        body.add("client_secret", microsoftClientSecret);
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                request,
                Map.class
            );

            if (response != null && response.containsKey("access_token")) {
                String newAccessToken = (String) response.get("access_token");
                account.setAccessToken(newAccessToken);
                // Microsoft may return a new refresh token
                if (response.containsKey("refresh_token")) {
                    account.setRefreshToken((String) response.get("refresh_token"));
                }
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
     * Fetch emails from Outlook inbox with pagination
     */
    public Map<String, Object> fetchInbox(Long accountId, int maxResults) {
        return fetchInbox(accountId, maxResults, null, null, null);
    }

    public Map<String, Object> fetchInbox(Long accountId, int maxResults, String skipToken) {
        return fetchInbox(accountId, maxResults, skipToken, null, null);
    }

    /**
     * Fetch ALL emails for analytics sync (includes both main mailbox and archive)
     */
    public Map<String, Object> fetchAllEmails(Long accountId, int maxResults, String skipToken) {
        return fetchAllEmails(accountId, maxResults, skipToken, false);
    }

    /**
     * Fetch ALL emails including archive for analytics sync
     * @param includeArchive whether to also fetch from Online Archive
     */
    public Map<String, Object> fetchAllEmails(Long accountId, int maxResults, String skipToken, boolean includeArchive) {
        // First fetch from main mailbox
        Map<String, Object> result = fetchInbox(accountId, maxResults, skipToken, null, null);

        // If we're done with main mailbox and need to include archive
        if (includeArchive && (result.get("nextPageToken") == null || skipToken != null && skipToken.startsWith("archive:"))) {
            try {
                EmailAccount account = emailAccountRepository.findById(accountId)
                    .orElseThrow(() -> new RuntimeException("Account not found"));

                String accessToken = account.getAccessToken();
                if (account.getTokenExpiresAt() != null &&
                    account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
                    accessToken = refreshAccessToken(account);
                }

                Map<String, Object> archiveResult = fetchArchiveEmailsWithToken(account, accessToken, maxResults,
                    skipToken != null && skipToken.startsWith("archive:") ? skipToken.substring(8) : null, true);

                // Merge results
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mainEmails = (List<Map<String, Object>>) result.get("emails");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> archiveEmails = (List<Map<String, Object>>) archiveResult.get("emails");

                List<Map<String, Object>> combined = new ArrayList<>(mainEmails);
                combined.addAll(archiveEmails);
                result.put("emails", combined);

                if (archiveResult.get("nextPageToken") != null) {
                    result.put("nextPageToken", "archive:" + archiveResult.get("nextPageToken"));
                }
            } catch (Exception e) {
                System.out.println("Could not fetch archive emails: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Fetch emails from Online Archive
     */
    private Map<String, Object> fetchArchiveEmailsWithToken(EmailAccount account, String accessToken, int maxResults, String skipToken, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url;
            if (skipToken != null && !skipToken.isEmpty()) {
                url = skipToken;
            } else {
                url = GRAPH_API_BETA + "/mailFolders/archive/messages?$top=" + maxResults +
                    "&$select=id,subject,from,toRecipients,receivedDateTime,bodyPreview,isRead,conversationId,parentFolderId" +
                    "&$orderby=receivedDateTime desc";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (response == null || !response.containsKey("value")) {
                return Map.of("emails", List.of(), "total", 0);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("value");

            List<Map<String, Object>> emails = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                Map<String, Object> email = new HashMap<>(convertMessage(msg));
                email.put("isArchive", true);
                emails.add(email);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("emails", emails);
            result.put("total", emails.size());
            if (response.containsKey("@odata.nextLink")) {
                result.put("nextPageToken", response.get("@odata.nextLink"));
            }
            return result;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return fetchArchiveEmailsWithToken(account, newToken, maxResults, skipToken, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        } catch (Exception e) {
            System.out.println("Archive fetch error: " + e.getMessage());
            return Map.of("emails", List.of(), "total", 0);
        }
    }

    /**
     * Fetch emails with date range filtering for multi-account pagination
     * @param before ISO date string (exclusive) - fetch emails before this date
     * @param after ISO date string (inclusive) - fetch emails after this date
     */
    public Map<String, Object> fetchInbox(Long accountId, int maxResults, String skipToken, String before, String after) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        if (account.getProvider() != EmailAccount.EmailProvider.OUTLOOK) {
            throw new RuntimeException("Account is not an Outlook account");
        }

        String accessToken = account.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            throw new RuntimeException("No access token available");
        }

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            System.out.println("Outlook token expiring soon, proactively refreshing...");
            accessToken = refreshAccessToken(account);
        }

        return fetchInboxWithToken(account, accessToken, maxResults, skipToken, before, after, true);
    }

    private Map<String, Object> fetchInboxWithToken(EmailAccount account, String accessToken, int maxResults, String skipToken, String before, String after, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // Microsoft Graph API for messages (all folders, not just inbox)
            String url;
            if (skipToken != null && !skipToken.isEmpty()) {
                // Use the full nextLink URL stored as skipToken
                url = skipToken;
            } else {
                // Build date filter for Microsoft Graph
                StringBuilder filter = new StringBuilder();
                if (before != null && !before.isEmpty()) {
                    filter.append("receivedDateTime lt ").append(before);
                }
                if (after != null && !after.isEmpty()) {
                    if (filter.length() > 0) filter.append(" and ");
                    filter.append("receivedDateTime ge ").append(after);
                }

                url = GRAPH_API_BASE + "/messages?$top=" + maxResults +
                    "&$select=id,subject,from,toRecipients,receivedDateTime,bodyPreview,isRead,conversationId,parentFolderId" +
                    "&$orderby=receivedDateTime desc";

                if (filter.length() > 0) {
                    url += "&$filter=" + java.net.URLEncoder.encode(filter.toString(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (response == null || !response.containsKey("value")) {
                return Map.of("emails", List.of(), "total", 0);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("value");

            List<Map<String, Object>> emails = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                emails.add(convertMessage(msg));
            }

            // Update sync status
            account.setSyncStatus(EmailAccount.SyncStatus.SYNCED);
            account.setLastSyncAt(java.time.LocalDateTime.now());
            emailAccountRepository.save(account);

            Map<String, Object> result = new HashMap<>();
            result.put("emails", emails);
            result.put("total", emails.size());
            // Microsoft Graph uses @odata.nextLink for pagination
            if (response.containsKey("@odata.nextLink")) {
                result.put("nextPageToken", response.get("@odata.nextLink"));
            }
            return result;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                System.out.println("Access token expired, attempting refresh...");
                String newToken = refreshAccessToken(account);
                return fetchInboxWithToken(account, newToken, maxResults, skipToken, before, after, false);
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

    private Map<String, Object> convertMessage(Map<String, Object> msg) {
        // Extract from address
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) msg.get("from");
        String fromStr = "";
        if (from != null) {
            @SuppressWarnings("unchecked")
            Map<String, String> emailAddress = (Map<String, String>) from.get("emailAddress");
            if (emailAddress != null) {
                String name = emailAddress.get("name");
                String email = emailAddress.get("address");
                fromStr = (name != null && !name.isEmpty()) ? name + " <" + email + ">" : email;
            }
        }

        // Extract to addresses
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toRecipients = (List<Map<String, Object>>) msg.get("toRecipients");
        String toStr = "";
        if (toRecipients != null && !toRecipients.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, String> firstTo = (Map<String, String>) toRecipients.get(0).get("emailAddress");
            if (firstTo != null) {
                toStr = firstTo.get("address");
            }
        }

        Boolean isRead = (Boolean) msg.get("isRead");

        return Map.of(
            "id", msg.getOrDefault("id", ""),
            "from", fromStr,
            "to", toStr,
            "subject", msg.getOrDefault("subject", ""),
            "date", msg.getOrDefault("receivedDateTime", ""),
            "snippet", msg.getOrDefault("bodyPreview", ""),
            "isUnread", isRead != null && !isRead,
            "threadId", msg.getOrDefault("conversationId", "")
        );
    }

    /**
     * Fetch a single email with full body
     */
    public Map<String, Object> fetchEmail(Long accountId, String messageId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            System.out.println("Outlook token expiring soon, proactively refreshing...");
            accessToken = refreshAccessToken(account);
        }

        try {
            return fetchEmailWithToken(account, accessToken, messageId, true);
        } catch (HttpClientErrorException.Unauthorized e) {
            String newToken = refreshAccessToken(account);
            return fetchEmailWithToken(account, newToken, messageId, false);
        }
    }

    private Map<String, Object> fetchEmailWithToken(EmailAccount account, String accessToken, String messageId, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = GRAPH_API_BASE + "/messages/" + messageId;

            @SuppressWarnings("unchecked")
            Map<String, Object> msg = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (msg == null) {
                throw new RuntimeException("Message not found");
            }

            // Extract from address
            @SuppressWarnings("unchecked")
            Map<String, Object> from = (Map<String, Object>) msg.get("from");
            String fromStr = "";
            if (from != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> emailAddress = (Map<String, String>) from.get("emailAddress");
                if (emailAddress != null) {
                    String name = emailAddress.get("name");
                    String email = emailAddress.get("address");
                    fromStr = (name != null && !name.isEmpty()) ? name + " <" + email + ">" : email;
                }
            }

            // Extract to addresses
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toRecipients = (List<Map<String, Object>>) msg.get("toRecipients");
            String toStr = "";
            if (toRecipients != null && !toRecipients.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, String> firstTo = (Map<String, String>) toRecipients.get(0).get("emailAddress");
                if (firstTo != null) {
                    toStr = firstTo.get("address");
                }
            }

            // Get body
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) msg.get("body");
            String bodyContent = "";
            if (body != null) {
                bodyContent = (String) body.getOrDefault("content", "");
            }

            return Map.of(
                "id", messageId,
                "from", fromStr,
                "to", toStr,
                "subject", msg.getOrDefault("subject", ""),
                "date", msg.getOrDefault("receivedDateTime", ""),
                "body", bodyContent,
                "snippet", msg.getOrDefault("bodyPreview", "")
            );

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return fetchEmailWithToken(account, newToken, messageId, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Fetch all mail folders for an Outlook account
     */
    public List<Map<String, Object>> fetchFolders(Long accountId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            System.out.println("Outlook token expiring soon, proactively refreshing...");
            accessToken = refreshAccessToken(account);
        }

        return fetchFoldersWithToken(account, accessToken, true);
    }

    private List<Map<String, Object>> fetchFoldersWithToken(EmailAccount account, String accessToken, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            List<Map<String, Object>> result = new ArrayList<>();

            // Fetch main mailbox folders
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                GRAPH_API_BASE + "/mailFolders?$top=50",
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (response != null && response.containsKey("value")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> folders = (List<Map<String, Object>>) response.get("value");

                for (Map<String, Object> folder : folders) {
                    result.add(Map.of(
                        "id", folder.getOrDefault("id", ""),
                        "name", folder.getOrDefault("displayName", ""),
                        "type", "user",
                        "messageCount", folder.getOrDefault("totalItemCount", 0),
                        "unreadCount", folder.getOrDefault("unreadItemCount", 0)
                    ));
                }
            }

            // Fetch Online Archive folders (using beta API)
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> archiveResponse = restTemplate.exchange(
                    GRAPH_API_BETA + "/mailFolders/archive/childFolders?$top=100",
                    HttpMethod.GET,
                    entity,
                    Map.class
                ).getBody();

                if (archiveResponse != null && archiveResponse.containsKey("value")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> archiveFolders = (List<Map<String, Object>>) archiveResponse.get("value");

                    for (Map<String, Object> folder : archiveFolders) {
                        Map<String, Object> folderMap = new HashMap<>();
                        folderMap.put("id", folder.getOrDefault("id", ""));
                        folderMap.put("name", "[Archive] " + folder.getOrDefault("displayName", ""));
                        folderMap.put("type", "archive");
                        folderMap.put("messageCount", folder.getOrDefault("totalItemCount", 0));
                        folderMap.put("unreadCount", folder.getOrDefault("unreadItemCount", 0));
                        folderMap.put("isArchive", true);
                        result.add(folderMap);
                    }
                }

                // Also add the root archive folder itself
                @SuppressWarnings("unchecked")
                Map<String, Object> archiveRootResponse = restTemplate.exchange(
                    GRAPH_API_BETA + "/mailFolders/archive",
                    HttpMethod.GET,
                    entity,
                    Map.class
                ).getBody();

                if (archiveRootResponse != null) {
                    Map<String, Object> archiveRoot = new HashMap<>();
                    archiveRoot.put("id", archiveRootResponse.getOrDefault("id", ""));
                    archiveRoot.put("name", "[Archive] All Mail");
                    archiveRoot.put("type", "archive");
                    archiveRoot.put("messageCount", archiveRootResponse.getOrDefault("totalItemCount", 0));
                    archiveRoot.put("unreadCount", archiveRootResponse.getOrDefault("unreadItemCount", 0));
                    archiveRoot.put("isArchive", true);
                    // Add at beginning of archive section
                    result.add(archiveRoot);
                }
            } catch (Exception archiveError) {
                // Online Archive may not be available for all accounts (requires M365 license)
                System.out.println("Online Archive not available or accessible: " + archiveError.getMessage());
            }

            return result;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return fetchFoldersWithToken(account, newToken, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Fetch emails by folder
     */
    public Map<String, Object> fetchByFolder(Long accountId, String folderId, int maxResults) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            System.out.println("Outlook token expiring soon, proactively refreshing...");
            accessToken = refreshAccessToken(account);
        }

        return fetchByFolderWithToken(account, accessToken, folderId, maxResults, true);
    }

    private Map<String, Object> fetchByFolderWithToken(EmailAccount account, String accessToken, String folderId, int maxResults, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            String url = GRAPH_API_BASE + "/mailFolders/" + folderId + "/messages?$top=" + maxResults +
                "&$select=id,subject,from,toRecipients,receivedDateTime,bodyPreview,isRead,conversationId" +
                "&$orderby=receivedDateTime desc";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                entity,
                Map.class
            ).getBody();

            if (response == null || !response.containsKey("value")) {
                return Map.of("emails", List.of(), "total", 0);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("value");

            List<Map<String, Object>> emails = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                emails.add(convertMessage(msg));
            }

            return Map.of(
                "emails", emails,
                "total", emails.size()
            );

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return fetchByFolderWithToken(account, newToken, folderId, maxResults, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Move email to a different folder
     */
    public void moveEmail(Long accountId, String messageId, String toFolderId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        // Proactively refresh if token expires within 5 minutes
        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            System.out.println("Outlook token expiring soon, proactively refreshing...");
            accessToken = refreshAccessToken(account);
        }

        moveEmailWithToken(account, accessToken, messageId, toFolderId, true);
    }

    private void moveEmailWithToken(EmailAccount account, String accessToken, String messageId, String toFolderId, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of("destinationId", toFolderId);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForObject(
                GRAPH_API_BASE + "/messages/" + messageId + "/move",
                entity,
                Map.class
            );

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                moveEmailWithToken(account, newToken, messageId, toFolderId, false);
            } else {
                throw new RuntimeException("Access token expired. Please re-authenticate.");
            }
        }
    }

    /**
     * Get folders in a format suitable for the bulk controller
     */
    public List<Map<String, Object>> getFolders(Long accountId) {
        return fetchFolders(accountId);
    }

    /**
     * Create a new mail folder
     */
    public String createFolder(Long accountId, String folderName, String parentFolderId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            accessToken = refreshAccessToken(account);
        }

        return createFolderWithToken(account, accessToken, folderName, parentFolderId, true);
    }

    private String createFolderWithToken(EmailAccount account, String accessToken, String folderName, String parentFolderId, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of("displayName", folderName);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);

            String url;
            if (parentFolderId != null && !parentFolderId.isEmpty()) {
                url = GRAPH_API_BASE + "/mailFolders/" + parentFolderId + "/childFolders";
            } else {
                url = GRAPH_API_BASE + "/mailFolders";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

            if (response != null && response.containsKey("id")) {
                return (String) response.get("id");
            }
            throw new RuntimeException("Failed to create folder");

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return createFolderWithToken(account, newToken, folderName, parentFolderId, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Get or create a folder by name (idempotent)
     */
    public String getOrCreateFolder(Long accountId, String folderName, String parentFolderId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            accessToken = refreshAccessToken(account);
        }

        return getOrCreateFolderWithToken(account, accessToken, folderName, parentFolderId, true);
    }

    private String getOrCreateFolderWithToken(EmailAccount account, String accessToken, String folderName, String parentFolderId, boolean allowRetry) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // Search for existing folder
            String url;
            if (parentFolderId != null && !parentFolderId.isEmpty()) {
                url = GRAPH_API_BASE + "/mailFolders/" + parentFolderId + "/childFolders?$filter=displayName eq '" +
                    folderName.replace("'", "''") + "'";
            } else {
                url = GRAPH_API_BASE + "/mailFolders?$filter=displayName eq '" +
                    folderName.replace("'", "''") + "'";
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class).getBody();

            if (response != null && response.containsKey("value")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> folders = (List<Map<String, Object>>) response.get("value");
                if (!folders.isEmpty()) {
                    return (String) folders.get(0).get("id");
                }
            }

            // Folder doesn't exist, create it
            return createFolderWithToken(account, accessToken, folderName, parentFolderId, false);

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return getOrCreateFolderWithToken(account, newToken, folderName, parentFolderId, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }

    /**
     * Move all emails from specified senders to a folder
     */
    public int moveEmailsBySenders(Long accountId, List<String> senderEmails, String toFolderId) {
        EmailAccount account = emailAccountRepository.findById(accountId)
            .orElseThrow(() -> new RuntimeException("Account not found"));

        String accessToken = account.getAccessToken();

        if (account.getTokenExpiresAt() != null &&
            account.getTokenExpiresAt().isBefore(java.time.LocalDateTime.now().plusMinutes(5))) {
            accessToken = refreshAccessToken(account);
        }

        return moveEmailsBySendersWithToken(account, accessToken, senderEmails, toFolderId, true);
    }

    private int moveEmailsBySendersWithToken(EmailAccount account, String accessToken, List<String> senderEmails, String toFolderId, boolean allowRetry) {
        try {
            int totalMoved = 0;

            for (String senderEmail : senderEmails) {
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(accessToken);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                // Search for emails from this sender, EXCLUDING those already in target folder
                String filter = "from/emailAddress/address eq '" + senderEmail.replace("'", "''") + "'" +
                    " and parentFolderId ne '" + toFolderId + "'";
                String url = GRAPH_API_BASE + "/messages?$filter=" +
                    java.net.URLEncoder.encode(filter, java.nio.charset.StandardCharsets.UTF_8) +
                    "&$select=id,parentFolderId&$top=100";

                String nextLink = url;
                while (nextLink != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> response = restTemplate.exchange(
                        nextLink,
                        HttpMethod.GET,
                        entity,
                        Map.class
                    ).getBody();

                    if (response == null || !response.containsKey("value")) {
                        break;
                    }

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> messages = (List<Map<String, Object>>) response.get("value");

                    for (Map<String, Object> msg : messages) {
                        String messageId = (String) msg.get("id");
                        // Double-check: skip if already in target folder
                        String parentFolder = (String) msg.get("parentFolderId");
                        if (toFolderId.equals(parentFolder)) {
                            continue;
                        }
                        try {
                            moveEmailWithToken(account, accessToken, messageId, toFolderId, false);
                            totalMoved++;
                        } catch (Exception e) {
                            System.err.println("Failed to move message " + messageId + ": " + e.getMessage());
                        }
                    }

                    nextLink = (String) response.get("@odata.nextLink");
                }
            }

            return totalMoved;

        } catch (HttpClientErrorException.Unauthorized e) {
            if (allowRetry) {
                String newToken = refreshAccessToken(account);
                return moveEmailsBySendersWithToken(account, newToken, senderEmails, toFolderId, false);
            }
            throw new RuntimeException("Access token expired. Please re-authenticate.");
        }
    }
}
