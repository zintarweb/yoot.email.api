package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "sync_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType type;

    // Progress tracking
    private int totalAccounts;
    private int processedAccounts;
    private int totalEmailsSynced;
    private int totalEmailsSkipped;
    private int totalEmailsProcessed;  // Total emails looked at (synced + skipped)
    private int estimatedTotalEmails;  // Estimated total emails to process

    private String currentAccount;  // Currently processing account email
    private String statusMessage;
    private int currentPage;         // Current page being processed
    private long emailsPerSecond;    // Processing rate for ETA calculation
    private int estimatedSecondsRemaining;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
        if (status == null) {
            status = JobStatus.PENDING;
        }
    }

    public enum JobStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum JobType {
        FULL_SYNC,
        INCREMENTAL_SYNC
    }

    public int getProgressPercent() {
        if (totalAccounts == 0) return 0;
        return (int) ((processedAccounts * 100.0) / totalAccounts);
    }
}
