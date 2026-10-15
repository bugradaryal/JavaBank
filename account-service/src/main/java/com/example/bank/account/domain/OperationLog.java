package com.example.bank.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * İşlenmiş komutların kaydı (idempotency). Anahtar: "transferId:ADIM".
 * Başarısız (iş kuralı reddi) sonuçlar da yazılır: aynı komut tekrar gelirse aynı cevap verilir.
 */
@Entity
@Table(name = "operation_log")
@Getter
public class OperationLog {

    @Id
    @Column(name = "op_key", length = 80)
    private String opKey;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    @Column(length = 255)
    private String reason;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OperationLog() {
        // JPA
    }

    public OperationLog(String opKey, boolean success, String reasonCode, String reason, Long customerId) {
        this.opKey = opKey;
        this.success = success;
        this.reasonCode = reasonCode;
        this.reason = reason == null || reason.length() <= 255 ? reason : reason.substring(0, 255);
        this.customerId = customerId;
        this.createdAt = Instant.now();
    }
}
