package com.emailutilities.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(nullable = false)
    private String emailAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailProvider provider;

    // IMAP settings
    private String imapHost;
    private Integer imapPort;
    private Boolean imapSsl;

    // SMTP settings
    private String smtpHost;
    private Integer smtpPort;
    private Boolean smtpTls;

    // Credentials (encrypted in production)
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String accessToken;

    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String refreshToken;

    @JsonIgnore
    private LocalDateTime tokenExpiresAt;

    private String username;

    @JsonIgnore
    @Column(length = 500)
    private String encryptedPassword;

    @Enumerated(EnumType.STRING)
    private SyncStatus syncStatus;

    private LocalDateTime lastSyncAt;
    private String lastSyncError;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (syncStatus == null) {
            syncStatus = SyncStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum EmailProvider {
        GMAIL, OUTLOOK, YAHOO, ICLOUD, IMAP_CUSTOM
    }

    public enum SyncStatus {
        PENDING, SYNCING, SYNCED, ERROR
    }
}
