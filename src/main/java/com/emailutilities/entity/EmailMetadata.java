package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_metadata", indexes = {
    @Index(name = "idx_sender_email", columnList = "senderEmail"),
    @Index(name = "idx_account_id", columnList = "accountId"),
    @Index(name = "idx_thread_id", columnList = "threadId"),
    @Index(name = "idx_received_at", columnList = "receivedAt")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, unique = true)
    private String messageId;

    private String threadId;

    @Column(nullable = false)
    private String senderEmail;

    private String senderName;

    private String recipientEmail;

    private String subject;

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    private boolean isRead;

    private boolean isFromMe;  // true if sent by the account owner

    private String inReplyTo;  // messageId this is replying to

    private LocalDateTime syncedAt;

    @PrePersist
    protected void onCreate() {
        syncedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        syncedAt = LocalDateTime.now();
    }
}
