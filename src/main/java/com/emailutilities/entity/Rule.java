package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    private String description;

    private Boolean enabled;

    // Priority for rule ordering (lower = higher priority)
    private Integer priority;

    // If true, stop processing other rules after this one matches
    private Boolean stopProcessing;

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RuleCondition> conditions = new ArrayList<>();

    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RuleAction> actions = new ArrayList<>();

    // How to combine conditions: ALL (AND) or ANY (OR)
    @Enumerated(EnumType.STRING)
    private ConditionLogic conditionLogic;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        if (priority == null) priority = 100;
        if (stopProcessing == null) stopProcessing = false;
        if (conditionLogic == null) conditionLogic = ConditionLogic.ALL;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ConditionLogic {
        ALL,  // AND - all conditions must match
        ANY   // OR - any condition can match
    }
}
