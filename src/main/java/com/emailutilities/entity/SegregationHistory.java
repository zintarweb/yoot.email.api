package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "segregation_history",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "sender_email"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SegregationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    @Column(name = "folder_name")
    private String folderName;  // The folder/label created for this sender

    @Column(name = "emails_moved")
    private int emailsMoved;  // Count of emails moved

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationType operationType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "run_count")
    private int runCount = 1;  // How many times this operation was run

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        lastRunAt = LocalDateTime.now();
    }

    public enum OperationType {
        SEGREGATE,  // Moved to Segregated/SenderName folder
        MOVE        // Moved to a custom folder
    }
}
