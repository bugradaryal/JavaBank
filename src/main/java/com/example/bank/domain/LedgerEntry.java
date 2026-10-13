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

/**
 * Hesap hareketi. Değişmez kayıt: bir kez yazılır, güncellenmez.
 * Bir hesabın bakiyesi = o hesabın CREDIT toplamı - DEBIT toplamı.
 */
@Entity
@Table(name = "ledger_entry")
@Getter
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "transfer_id", nullable = false)
    private Long transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6)
    private EntryType entryType;

    /** Her zaman pozitif; yönü entryType belirler. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // JPA
    }

    private LedgerEntry(Long accountId, Long transferId, EntryType entryType,
                        BigDecimal amount, BigDecimal balanceAfter) {
        this.accountId = accountId;
        this.transferId = transferId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    public static LedgerEntry debit(Long accountId, Long transferId, BigDecimal amount, BigDecimal balanceAfter) {
        return new LedgerEntry(accountId, transferId, EntryType.DEBIT, amount, balanceAfter);
    }

    public static LedgerEntry credit(Long accountId, Long transferId, BigDecimal amount, BigDecimal balanceAfter) {
        return new LedgerEntry(accountId, transferId, EntryType.CREDIT, amount, balanceAfter);
    }
}
