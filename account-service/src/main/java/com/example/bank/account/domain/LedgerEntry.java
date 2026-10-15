package com.example.bank.account.domain;

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
 * Transfer artık başka serviste yaşadığı için FK yok; reference "transferId:ADIM" biçimindeki işlem anahtarıdır.
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

    @Column(nullable = false, length = 80)
    private String reference;

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

    private LedgerEntry(Long accountId, String reference, EntryType entryType,
                        BigDecimal amount, BigDecimal balanceAfter) {
        this.accountId = accountId;
        this.reference = reference;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = Instant.now();
    }

    public static LedgerEntry debit(Long accountId, String reference, BigDecimal amount, BigDecimal balanceAfter) {
        return new LedgerEntry(accountId, reference, EntryType.DEBIT, amount, balanceAfter);
    }

    public static LedgerEntry credit(Long accountId, String reference, BigDecimal amount, BigDecimal balanceAfter) {
        return new LedgerEntry(accountId, reference, EntryType.CREDIT, amount, balanceAfter);
    }
}
