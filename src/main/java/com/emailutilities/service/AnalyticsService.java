package com.emailutilities.service;

import com.emailutilities.entity.EmailAccount;
import com.emailutilities.entity.EmailMetadata;
import com.emailutilities.repository.EmailAccountRepository;
import com.emailutilities.repository.EmailMetadataRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private final EmailMetadataRepository metadataRepository;
    private final EmailAccountRepository accountRepository;
    private final GmailService gmailService;
    private final OutlookService outlookService;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("<([^>]+)>|([\\w.+-]+@[\\w.-]+)");

    public AnalyticsService(EmailMetadataRepository metadataRepository,
                           EmailAccountRepository accountRepository,
                           GmailService gmailService,
                           OutlookService outlookService) {
        this.metadataRepository = metadataRepository;
        this.accountRepository = accountRepository;
        this.gmailService = gmailService;
        this.outlookService = outlookService;
    }

    /**
     * Sync email metadata from all accounts
     */
    public Map<String, Object> syncMetadata(Long userId) {
        List<EmailAccount> accounts = accountRepository.findByUserId(userId);
        int totalSynced = 0;
        int totalSkipped = 0;

        for (EmailAccount account : accounts) {
            try {
                Map<String, Integer> result = syncAccountMetadata(account);
                totalSynced += result.get("synced");
                totalSkipped += result.get("skipped");
            } catch (Exception e) {
                System.err.println("Failed to sync account " + account.getEmailAddress() + ": " + e.getMessage());
            }
        }

        return Map.of(
            "synced", totalSynced,
            "skipped", totalSkipped,
            "accounts", accounts.size()
        );
    }

    /**
     * Sync metadata for a single account
     */
    public Map<String, Integer> syncAccountMetadata(EmailAccount account) {
        int synced = 0;
        int skipped = 0;
        String pageToken = null;
        int maxPages = 10; // Limit initial sync
        int page = 0;

        String accountEmail = account.getEmailAddress().toLowerCase();

        while (page < maxPages) {
            Map<String, Object> result;

            if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                result = gmailService.fetchInbox(account.getId(), 100, pageToken);
            } else if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                result = outlookService.fetchInbox(account.getId(), 100, pageToken);
            } else {
                break;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> emails = (List<Map<String, Object>>) result.get("emails");

            if (emails == null || emails.isEmpty()) {
                break;
            }

            for (Map<String, Object> email : emails) {
                String messageId = (String) email.get("id");

                // Skip if already synced
                if (metadataRepository.existsByMessageId(messageId)) {
                    skipped++;
                    continue;
                }

                EmailMetadata metadata = new EmailMetadata();
                metadata.setAccountId(account.getId());
                metadata.setMessageId(messageId);
                metadata.setThreadId((String) email.get("threadId"));

                // Parse sender
                String from = (String) email.getOrDefault("from", "");
                String[] senderParts = parseEmailAddress(from);
                metadata.setSenderEmail(senderParts[0].toLowerCase());
                metadata.setSenderName(senderParts[1]);

                // Parse recipient
                String to = (String) email.getOrDefault("to", "");
                String[] recipientParts = parseEmailAddress(to);
                metadata.setRecipientEmail(recipientParts[0].toLowerCase());

                metadata.setSubject((String) email.getOrDefault("subject", ""));

                // Parse date
                String dateStr = (String) email.get("date");
                metadata.setReceivedAt(parseDate(dateStr));

                // Read status
                Object isUnread = email.get("isUnread");
                metadata.setRead(isUnread == null || !(Boolean) isUnread);

                // Determine if from me
                metadata.setFromMe(metadata.getSenderEmail().equalsIgnoreCase(accountEmail));

                // TODO: Parse In-Reply-To header when available

                metadataRepository.save(metadata);
                synced++;
            }

            pageToken = (String) result.get("nextPageToken");
            if (pageToken == null) {
                break;
            }
            page++;
        }

        return Map.of("synced", synced, "skipped", skipped);
    }

    /**
     * Get top senders
     */
    public List<Map<String, Object>> getTopSenders(List<Long> accountIds, int limit, Integer days) {
        List<Object[]> results;

        if (days != null) {
            LocalDateTime since = LocalDateTime.now().minusDays(days);
            results = metadataRepository.findTopSendersSince(accountIds, since);
        } else {
            results = metadataRepository.findTopSenders(accountIds);
        }

        return results.stream()
            .limit(limit)
            .map(row -> Map.<String, Object>of(
                "email", row[0],
                "name", row[1] != null ? row[1] : "",
                "count", ((Number) row[2]).intValue()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Get senders ranked by unread emails
     */
    public List<Map<String, Object>> getUnreadBySender(List<Long> accountIds, int limit) {
        List<Object[]> results = metadataRepository.findSendersByUnreadCount(accountIds);

        return results.stream()
            .limit(limit)
            .map(row -> Map.<String, Object>of(
                "email", row[0],
                "name", row[1] != null ? row[1] : "",
                "unreadCount", ((Number) row[2]).intValue()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Get reply-to ranking (who user replies to most)
     */
    public List<Map<String, Object>> getReplyToRanking(List<Long> accountIds, int limit) {
        List<Object[]> results = metadataRepository.countEmailsSentToRecipient(accountIds);

        return results.stream()
            .filter(row -> row[0] != null && !((String) row[0]).isEmpty())
            .limit(limit)
            .map(row -> Map.<String, Object>of(
                "email", row[0],
                "sentCount", ((Number) row[1]).intValue()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Get senders with low reply ratio
     */
    public List<Map<String, Object>> getLowReplyRatioSenders(List<Long> accountIds, int limit) {
        // Get emails received from each sender
        Map<String, Long> receivedCounts = metadataRepository.countEmailsReceivedBySender(accountIds)
            .stream()
            .collect(Collectors.toMap(
                row -> ((String) row[0]).toLowerCase(),
                row -> ((Number) row[1]).longValue()
            ));

        // Get replies sent to each recipient
        Map<String, Long> replyCounts = metadataRepository.countRepliesSentToRecipient(accountIds)
            .stream()
            .filter(row -> row[0] != null)
            .collect(Collectors.toMap(
                row -> ((String) row[0]).toLowerCase(),
                row -> ((Number) row[1]).longValue(),
                (a, b) -> a + b
            ));

        // Calculate reply ratio for each sender
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, Long> entry : receivedCounts.entrySet()) {
            String sender = entry.getKey();
            long received = entry.getValue();
            long replies = replyCounts.getOrDefault(sender, 0L);

            double ratio = received > 0 ? (double) replies / received : 0;

            results.add(Map.of(
                "email", sender,
                "received", received,
                "replies", replies,
                "replyRatio", Math.round(ratio * 100) / 100.0
            ));
        }

        // Sort by reply ratio (ascending) and filter senders with enough emails
        return results.stream()
            .filter(r -> ((Number) r.get("received")).longValue() >= 3) // At least 3 emails
            .sorted(Comparator.comparingDouble(r -> ((Number) r.get("replyRatio")).doubleValue()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Get summary statistics
     */
    public Map<String, Object> getSummaryStats(List<Long> accountIds) {
        Long totalEmails = metadataRepository.countTotalEmails(accountIds);
        Long unreadEmails = metadataRepository.countUnreadEmails(accountIds);
        Long uniqueSenders = metadataRepository.countUniqueSenders(accountIds);

        return Map.of(
            "totalEmails", totalEmails != null ? totalEmails : 0,
            "unreadEmails", unreadEmails != null ? unreadEmails : 0,
            "uniqueSenders", uniqueSenders != null ? uniqueSenders : 0,
            "readRatio", totalEmails > 0 ? Math.round((1 - (double) unreadEmails / totalEmails) * 100) : 0
        );
    }

    private String[] parseEmailAddress(String fromHeader) {
        if (fromHeader == null || fromHeader.isEmpty()) {
            return new String[]{"", ""};
        }

        Matcher matcher = EMAIL_PATTERN.matcher(fromHeader);
        String email = "";
        if (matcher.find()) {
            email = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }

        // Extract name (everything before the email)
        String name = fromHeader.replaceAll("<[^>]+>", "").trim();
        name = name.replaceAll("\"", "").trim();

        if (email.isEmpty()) {
            email = fromHeader.trim();
        }

        return new String[]{email, name};
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return LocalDateTime.now();
        }

        try {
            // Try ISO format first (Outlook)
            if (dateStr.contains("T")) {
                return LocalDateTime.parse(dateStr.substring(0, 19));
            }

            // Try RFC 2822 format (Gmail)
            // Example: "Tue, 31 Dec 2024 10:30:00 -0800"
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(
                "EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH
            );
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(dateStr, formatter);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            // Fallback
            return LocalDateTime.now();
        }
    }
}
