package com.example.bank.account.domain;

import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account")
@Getter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 34)
    private String iban;

    /** Kasa ve clearing hesaplarında null. */
    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;

    /** Yedek optimistic locking. Asıl koruma komut işlenirken alınan PESSIMISTIC_WRITE kilididir. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // JPA
    }

    public static Account open(String iban, Long customerId, Currency currency) {
        Account a = new Account();
        a.iban = iban;
        a.customerId = customerId;
        a.kind = AccountKind.CUSTOMER;
        a.currency = currency;
        a.balance = BigDecimal.ZERO.setScale(4);
        a.status = AccountStatus.ACTIVE;
        a.createdAt = Instant.now();
        return a;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /** Kasa hesapları eksiye düşebilir; müşteri ve clearing hesapları düşemez. */
    public boolean canDebit(BigDecimal amount) {
        return kind == AccountKind.CASH || balance.compareTo(amount) >= 0;
    }

    public void debit(BigDecimal amount) {
        if (!canDebit(amount)) {
            throw new BankException(ErrorCode.INSUFFICIENT_FUNDS, "Yetersiz bakiye");
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public void close() {
        if (balance.signum() != 0) {
            throw new BankException(ErrorCode.NON_ZERO_BALANCE, "Bakiyesi sıfır olmayan hesap kapatılamaz");
        }
        status = AccountStatus.CLOSED;
    }
}
