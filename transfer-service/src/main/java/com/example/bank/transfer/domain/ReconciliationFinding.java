package com.example.bank.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/** Mutabakatta bulunan tutarsızlık. İnsan inceleyip kapatana kadar açık kalır (resolved_at null). */
@Entity
@Table(name = "reconciliation_finding")
@Getter
public class ReconciliationFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(nullable = false, length = 500)
    private String details;

    @Column(name = "found_at", nullable = false, updatable = false)
    private Instant foundAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ReconciliationFinding() {
        // JPA
    }

    public ReconciliationFinding(Long transferId, String kind, String details) {
        this.transferId = transferId;
        this.kind = kind;
        this.details = details.length() <= 500 ? details : details.substring(0, 500);
        this.foundAt = Instant.now();
    }

    public void resolve() {
        this.resolvedAt = Instant.now();
    }
}
