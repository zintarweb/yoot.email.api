package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "rule_actions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionType actionType;

    // Action parameter (folder name, label, email to forward to, etc.)
    private String actionValue;

    // Order of execution within the rule
    private Integer executionOrder;

    @PrePersist
    protected void onCreate() {
        if (executionOrder == null) {
            executionOrder = 0;
        }
    }

    public enum ActionType {
        MOVE_TO_FOLDER,
        COPY_TO_FOLDER,
        APPLY_LABEL,
        REMOVE_LABEL,
        MARK_AS_READ,
        MARK_AS_UNREAD,
        STAR,
        UNSTAR,
        FLAG_IMPORTANT,
        FORWARD_TO,
        DELETE,
        MARK_AS_SPAM,
        ADD_TO_WHITELIST,
        ADD_TO_BLACKLIST
    }
}
