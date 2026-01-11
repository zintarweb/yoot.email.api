package com.emailutilities.controller;

import com.emailutilities.entity.EmailAccount;
import com.emailutilities.entity.SyncJob;
import com.emailutilities.repository.EmailAccountRepository;
import com.emailutilities.repository.SyncJobRepository;
import com.emailutilities.service.AnalyticsService;
import com.emailutilities.service.BackgroundSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final BackgroundSyncService backgroundSyncService;
    private final SyncJobRepository syncJobRepository;
    private final EmailAccountRepository accountRepository;

    public AnalyticsController(AnalyticsService analyticsService,
                              BackgroundSyncService backgroundSyncService,
                              SyncJobRepository syncJobRepository,
                              EmailAccountRepository accountRepository) {
        this.analyticsService = analyticsService;
        this.backgroundSyncService = backgroundSyncService;
        this.syncJobRepository = syncJobRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Start background sync job
     */
    @PostMapping("/sync")
    public ResponseEntity<?> startSync(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "FULL_SYNC") String type) {
        try {
            Long effectiveUserId = userId != null ? userId : 1L;
            SyncJob.JobType jobType = SyncJob.JobType.valueOf(type);
            SyncJob job = backgroundSyncService.startSyncJob(effectiveUserId, jobType);
            return ResponseEntity.ok(Map.of(
                "jobId", job.getId(),
                "status", job.getStatus(),
                "message", "Sync job started"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get sync job status
     */
    @GetMapping("/sync/status")
    public ResponseEntity<?> getSyncStatus(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            Long effectiveUserId = userId != null ? userId : 1L;
            // Get the most recent running or completed job
            return syncJobRepository.findFirstByUserIdAndStatusOrderByStartedAtDesc(effectiveUserId, SyncJob.JobStatus.RUNNING)
                .or(() -> syncJobRepository.findByUserIdOrderByStartedAtDesc(effectiveUserId).stream().findFirst())
                .map(job -> ResponseEntity.ok(buildJobStatusMap(job)))
                .orElse(ResponseEntity.ok(Map.of("status", "NO_JOBS")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get specific job status
     */
    @GetMapping("/sync/job/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable Long jobId) {
        try {
            return syncJobRepository.findById(jobId)
                .map(job -> ResponseEntity.ok(buildJobStatusMap(job)))
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cancel running sync job
     */
    @PostMapping("/sync/cancel/{jobId}")
    public ResponseEntity<?> cancelSync(@PathVariable Long jobId) {
        try {
            backgroundSyncService.cancelJob(jobId);
            return ResponseEntity.ok(Map.of("message", "Job cancelled"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get top senders
     */
    @GetMapping("/top-senders")
    public ResponseEntity<?> getTopSenders(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Integer days) {
        try {
            List<Long> accountIds = getAccountIds(userId);
            List<Map<String, Object>> results = analyticsService.getTopSenders(accountIds, limit, days);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get senders ranked by unread emails
     */
    @GetMapping("/unread-by-sender")
    public ResponseEntity<?> getUnreadBySender(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<Long> accountIds = getAccountIds(userId);
            List<Map<String, Object>> results = analyticsService.getUnreadBySender(accountIds, limit);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get reply-to ranking (who user replies to most)
     */
    @GetMapping("/reply-ranking")
    public ResponseEntity<?> getReplyRanking(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<Long> accountIds = getAccountIds(userId);
            List<Map<String, Object>> results = analyticsService.getReplyToRanking(accountIds, limit);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get senders with low reply ratio
     */
    @GetMapping("/low-reply-ratio")
    public ResponseEntity<?> getLowReplyRatio(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            List<Long> accountIds = getAccountIds(userId);
            List<Map<String, Object>> results = analyticsService.getLowReplyRatioSenders(accountIds, limit);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get summary statistics
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary(@RequestHeader(value = "X-User-Id", required = false) Long userId) {
        try {
            List<Long> accountIds = getAccountIds(userId);
            Map<String, Object> stats = analyticsService.getSummaryStats(accountIds);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get all analytics data in one call
     */
    @GetMapping
    public ResponseEntity<?> getAllAnalytics(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Integer days) {
        try {
            List<Long> accountIds = getAccountIds(userId);

            Map<String, Object> analytics = Map.of(
                "summary", analyticsService.getSummaryStats(accountIds),
                "topSenders", analyticsService.getTopSenders(accountIds, limit, days),
                "unreadBySender", analyticsService.getUnreadBySender(accountIds, limit),
                "replyRanking", analyticsService.getReplyToRanking(accountIds, limit),
                "lowReplyRatio", analyticsService.getLowReplyRatioSenders(accountIds, limit)
            );

            return ResponseEntity.ok(analytics);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<Long> getAccountIds(Long userId) {
        Long effectiveUserId = userId != null ? userId : 1L;
        return accountRepository.findByUserId(effectiveUserId).stream()
            .map(EmailAccount::getId)
            .collect(Collectors.toList());
    }

    private Map<String, Object> buildJobStatusMap(SyncJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", job.getId());
        map.put("status", job.getStatus());
        map.put("progress", job.getProgressPercent());
        map.put("totalAccounts", job.getTotalAccounts());
        map.put("processedAccounts", job.getProcessedAccounts());
        map.put("totalEmailsSynced", job.getTotalEmailsSynced());
        map.put("totalEmailsSkipped", job.getTotalEmailsSkipped());
        map.put("totalEmailsProcessed", job.getTotalEmailsProcessed());
        map.put("currentPage", job.getCurrentPage());
        map.put("emailsPerSecond", job.getEmailsPerSecond());
        map.put("estimatedSecondsRemaining", job.getEstimatedSecondsRemaining());
        map.put("currentAccount", job.getCurrentAccount() != null ? job.getCurrentAccount() : "");
        map.put("statusMessage", job.getStatusMessage() != null ? job.getStatusMessage() : "");
        map.put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toString() : "");
        map.put("completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : "");
        return map;
    }
}
