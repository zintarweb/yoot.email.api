package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "email_list_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailListEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_list_id", nullable = false)
    private EmailList emailList;

    // Can be full email or domain (e.g., "@example.com")
    @Column(nullable = false)
    private String pattern;

    @Enumerated(EnumType.STRING)
    private MatchType matchType;

    private String notes;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (matchType == null) {
            matchType = pattern.startsWith("@") ? MatchType.DOMAIN : MatchType.EXACT;
        }
    }

    public enum MatchType {
        EXACT,      // exact email match
        DOMAIN,     // matches all emails from domain
        CONTAINS    // pattern contained in email
    }
}
