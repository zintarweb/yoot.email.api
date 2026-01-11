package com.emailutilities.service;

import com.emailutilities.entity.*;
import com.emailutilities.repository.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BackgroundSyncService {

    private final SyncJobRepository syncJobRepository;
    private final NotificationRepository notificationRepository;
    private final EmailAccountRepository accountRepository;
    private final EmailMetadataRepository metadataRepository;
    private final GmailService gmailService;
    private final OutlookService outlookService;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("<([^>]+)>|([\\w.+-]+@[\\w.-]+)");

    public BackgroundSyncService(SyncJobRepository syncJobRepository,
                                 NotificationRepository notificationRepository,
                                 EmailAccountRepository accountRepository,
                                 EmailMetadataRepository metadataRepository,
                                 GmailService gmailService,
                                 OutlookService outlookService) {
        this.syncJobRepository = syncJobRepository;
        this.notificationRepository = notificationRepository;
        this.accountRepository = accountRepository;
        this.metadataRepository = metadataRepository;
        this.gmailService = gmailService;
        this.outlookService = outlookService;
    }

    /**
     * Start a new sync job
     */
    public SyncJob startSyncJob(Long userId, SyncJob.JobType type) {
        // Check if there's already a running job
        if (syncJobRepository.existsByUserIdAndStatus(userId, SyncJob.JobStatus.RUNNING)) {
            throw new RuntimeException("A sync job is already running");
        }

        List<EmailAccount> accounts = accountRepository.findByUserId(userId);

        SyncJob job = new SyncJob();
        job.setUserId(userId);
        job.setType(type);
        job.setStatus(SyncJob.JobStatus.PENDING);
        job.setTotalAccounts(accounts.size());
        job.setProcessedAccounts(0);
        job.setTotalEmailsSynced(0);
        job.setTotalEmailsSkipped(0);
        job.setStatusMessage("Starting sync...");

        syncJobRepository.save(job);

        // Start async processing
        processJobAsync(job.getId(), userId);

        return job;
    }

    /**
     * Process the sync job asynchronously
     */
    @Async
    public void processJobAsync(Long jobId, Long userId) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        try {
            job.setStatus(SyncJob.JobStatus.RUNNING);
            job.setStatusMessage("Fetching accounts...");
            syncJobRepository.save(job);

            List<EmailAccount> accounts = accountRepository.findByUserId(userId);
            job.setTotalAccounts(accounts.size());

            int totalSynced = 0;
            int totalSkipped = 0;

            for (int i = 0; i < accounts.size(); i++) {
                EmailAccount account = accounts.get(i);

                // Check if job was cancelled
                job = syncJobRepository.findById(jobId).orElse(null);
                if (job == null || job.getStatus() == SyncJob.JobStatus.CANCELLED) {
                    return;
                }

                job.setCurrentAccount(account.getEmailAddress());
                job.setStatusMessage("Syncing " + account.getEmailAddress() + "...");
                job.setProcessedAccounts(i);
                syncJobRepository.save(job);

                try {
                    Map<String, Integer> result = syncAccountEmails(account, job);
                    totalSynced += result.get("synced");
                    totalSkipped += result.get("skipped");

                    // Refresh job from DB to get updated values
                    job = syncJobRepository.findById(jobId).orElse(job);
                    job.setTotalEmailsSynced(totalSynced);
                    job.setTotalEmailsSkipped(totalSkipped);
                    syncJobRepository.save(job);
                } catch (Exception e) {
                    System.err.println("Error syncing " + account.getEmailAddress() + ": " + e.getMessage());
                    // Continue with other accounts
                }
            }

            // Job completed successfully
            job.setStatus(SyncJob.JobStatus.COMPLETED);
            job.setProcessedAccounts(accounts.size());
            job.setCompletedAt(LocalDateTime.now());
            job.setStatusMessage("Sync completed successfully");
            job.setCurrentAccount(null);
            syncJobRepository.save(job);

            // Send notification
            createNotification(userId, job);

        } catch (Exception e) {
            job.setStatus(SyncJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            job.setStatusMessage("Sync failed: " + e.getMessage());
            syncJobRepository.save(job);

            // Send failure notification
            createNotification(userId, job);
        }
    }

    /**
     * Sync emails for a single account with progress tracking
     */
    private Map<String, Integer> syncAccountEmails(EmailAccount account, SyncJob job) {
        int synced = 0;
        int skipped = 0;
        String pageToken = null;
        int maxPages = 50; // Allow more pages for background sync
        int page = 0;
        long startTime = System.currentTimeMillis();
        int emailsProcessedThisAccount = 0;

        String accountEmail = account.getEmailAddress().toLowerCase();

        while (page < maxPages) {
            // Check if job was cancelled
            SyncJob currentJob = syncJobRepository.findById(job.getId()).orElse(null);
            if (currentJob == null || currentJob.getStatus() == SyncJob.JobStatus.CANCELLED) {
                break;
            }

            Map<String, Object> result;

            if (account.getProvider() == EmailAccount.EmailProvider.GMAIL) {
                // Use fetchAllEmails to get all emails, not just INBOX
                result = gmailService.fetchAllEmails(account.getId(), 100, pageToken);
            } else if (account.getProvider() == EmailAccount.EmailProvider.OUTLOOK) {
                result = outlookService.fetchAllEmails(account.getId(), 100, pageToken);
            } else {
                break;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> emails = (List<Map<String, Object>>) result.get("emails");

            if (emails == null || emails.isEmpty()) {
                break;
            }

            // Batch check for existing message IDs (more efficient)
            List<String> messageIds = emails.stream()
                .map(e -> (String) e.get("id"))
                .collect(Collectors.toList());
            Set<String> existingIds = new HashSet<>(
                metadataRepository.findExistingMessageIds(messageIds)
            );

            for (Map<String, Object> email : emails) {
                String messageId = (String) email.get("id");
                emailsProcessedThisAccount++;

                // Skip if already synced (using batch result)
                if (existingIds.contains(messageId)) {
                    skipped++;
                    continue;
                }

                EmailMetadata metadata = new EmailMetadata();
                metadata.setAccountId(account.getId());
                metadata.setMessageId(messageId);
                metadata.setThreadId((String) email.get("threadId"));

                String from = (String) email.getOrDefault("from", "");
                String[] senderParts = parseEmailAddress(from);
                metadata.setSenderEmail(senderParts[0].toLowerCase());
                metadata.setSenderName(senderParts[1]);

                String to = (String) email.getOrDefault("to", "");
                String[] recipientParts = parseEmailAddress(to);
                metadata.setRecipientEmail(recipientParts[0].toLowerCase());

                metadata.setSubject((String) email.getOrDefault("subject", ""));
                metadata.setReceivedAt(parseDate((String) email.get("date")));

                Object isUnread = email.get("isUnread");
                metadata.setRead(isUnread == null || !(Boolean) isUnread);
                metadata.setFromMe(metadata.getSenderEmail().equalsIgnoreCase(accountEmail));

                metadataRepository.save(metadata);
                synced++;
            }

            // Update job progress after each page
            page++;
            long elapsedMs = System.currentTimeMillis() - startTime;
            long emailsPerSecond = elapsedMs > 0 ? (emailsProcessedThisAccount * 1000L) / elapsedMs : 0;

            job.setCurrentPage(page);
            job.setTotalEmailsSynced(job.getTotalEmailsSynced() - (synced - (synced > 0 ? synced : 0)) + synced);
            job.setTotalEmailsSkipped(job.getTotalEmailsSkipped() - (skipped - (skipped > 0 ? skipped : 0)) + skipped);
            job.setTotalEmailsProcessed(job.getTotalEmailsProcessed() + emails.size());
            job.setEmailsPerSecond(emailsPerSecond);
            job.setStatusMessage(String.format("Syncing %s (page %d) - %d/s",
                account.getEmailAddress(), page, emailsPerSecond));

            // Estimate remaining time
            int remaining = job.getEstimatedTotalEmails() - job.getTotalEmailsProcessed();
            if (emailsPerSecond > 0 && remaining > 0) {
                job.setEstimatedSecondsRemaining((int)(remaining / emailsPerSecond));
            }

            syncJobRepository.save(job);

            pageToken = (String) result.get("nextPageToken");
            if (pageToken == null) {
                break;
            }

            // Small delay to avoid rate limiting
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return Map.of("synced", synced, "skipped", skipped);
    }

    /**
     * Create a notification for job completion
     */
    private void createNotification(Long userId, SyncJob job) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setRelatedJobId(job.getId());
        notification.setActionUrl("/analytics");

        if (job.getStatus() == SyncJob.JobStatus.COMPLETED) {
            notification.setType(Notification.NotificationType.SYNC_COMPLETE);
            notification.setTitle("Sync Complete");
            notification.setMessage(String.format(
                "Successfully synced %d emails from %d accounts. %d emails were already synced.",
                job.getTotalEmailsSynced(),
                job.getTotalAccounts(),
                job.getTotalEmailsSkipped()
            ));
        } else {
            notification.setType(Notification.NotificationType.SYNC_FAILED);
            notification.setTitle("Sync Failed");
            notification.setMessage("Sync failed: " + job.getErrorMessage());
        }

        notificationRepository.save(notification);
    }

    /**
     * Cancel a running job
     */
    public void cancelJob(Long jobId) {
        SyncJob job = syncJobRepository.findById(jobId).orElse(null);
        if (job != null && job.getStatus() == SyncJob.JobStatus.RUNNING) {
            job.setStatus(SyncJob.JobStatus.CANCELLED);
            job.setCompletedAt(LocalDateTime.now());
            job.setStatusMessage("Cancelled by user");
            syncJobRepository.save(job);
        }
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
            if (dateStr.contains("T")) {
                return LocalDateTime.parse(dateStr.substring(0, 19));
            }

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern(
                "EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH
            );
            java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(dateStr, formatter);
            return zdt.toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
