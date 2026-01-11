package com.emailutilities.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "rule_conditions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private Rule rule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConditionField field;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConditionOperator operator;

    @Column(name = "condition_value", nullable = false)
    private String value;

    public enum ConditionField {
        FROM,           // sender email/domain
        TO,             // recipient email
        SUBJECT,        // email subject
        BODY,           // email body
        HAS_ATTACHMENT, // boolean
        ATTACHMENT_TYPE,// file extension
        ATTACHMENT_SIZE,// in bytes
        DATE_RECEIVED,  // datetime
        IS_REPLY,       // boolean
        IS_FORWARD      // boolean
    }

    public enum ConditionOperator {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        NOT_CONTAINS,
        STARTS_WITH,
        ENDS_WITH,
        MATCHES_REGEX,
        GREATER_THAN,
        LESS_THAN,
        IS_TRUE,
        IS_FALSE
    }
}
