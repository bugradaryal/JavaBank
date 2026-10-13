package com.example.bank.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transfer")
@Getter
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "from_account_id", nullable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false)
    private Long toAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransferStatus status;

    @Column(length = 255)
    private String description;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "initiated_by", nullable = false, length = 200)
    private String initiatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transfer() {
        // JPA
    }

    public static Transfer pending(String idempotencyKey, Long fromAccountId, Long toAccountId,
                                   BigDecimal amount, Currency currency, TransferType type,
                                   String description, String initiatedBy) {
        Transfer t = new Transfer();
        t.idempotencyKey = idempotencyKey;
        t.fromAccountId = fromAccountId;
        t.toAccountId = toAccountId;
        t.amount = amount;
        t.currency = currency;
        t.type = type;
        t.status = TransferStatus.PENDING;
        t.description = description;
        t.initiatedBy = initiatedBy;
        t.createdAt = Instant.now();
        return t;
    }

    public void complete() {
        this.status = TransferStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }
}
