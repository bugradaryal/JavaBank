package com.example.bank.transfer.domain;

import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.domain.TransferType;
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

/** Transfer + saga durumu. Tüm geçişler orkestratör tarafından, satır kilidi altında yapılır. */
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
    @Column(nullable = false, length = 20)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_state", nullable = false, length = 20)
    private SagaState sagaState;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "initiated_by", nullable = false, length = 200)
    private String initiatedBy;

    /** İsteği yapan müşteri (personel isteğinde null). Kaynak hesabın bu müşteriye ait olması zorunlu kılınır. */
    @Column(name = "owner_customer_id")
    private Long ownerCustomerId;

    @Column(name = "from_customer_id")
    private Long fromCustomerId;

    @Column(name = "to_customer_id")
    private Long toCustomerId;

    @Column(length = 255)
    private String description;

    /** Mevcut adımın kaç kez yeniden gönderildiği (recovery job). */
    @Column(nullable = false)
    private int attempts;

    /** Son adımın gönderildiği an; recovery job takılanları buna bakarak bulur. */
    @Column(name = "last_step_at", nullable = false)
    private Instant lastStepAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Mutabakat (reconciliation) yapıldığı an; null = henüz account-service kayıtlarıyla karşılaştırılmadı. */
    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    protected Transfer() {
        // JPA
    }

    public static Transfer start(String idempotencyKey, Long fromAccountId, Long toAccountId, BigDecimal amount,
                                 Currency currency, TransferType type, String description,
                                 String initiatedBy, Long ownerCustomerId) {
        Transfer t = new Transfer();
        t.idempotencyKey = idempotencyKey;
        t.fromAccountId = fromAccountId;
        t.toAccountId = toAccountId;
        t.amount = amount;
        t.currency = currency;
        t.type = type;
        t.description = description;
        t.initiatedBy = initiatedBy;
        t.ownerCustomerId = ownerCustomerId;
        t.sagaState = SagaState.STARTED;
        Instant now = Instant.now();
        t.createdAt = now;
        t.updatedAt = now;
        t.lastStepAt = now;
        return t;
    }

    public TransferStatus status() {
        return sagaState.status();
    }

    /** Para yatırmada kaynak kasa hesabı, diğer tiplerde müşteri hesabıdır. */
    public AccountKind sourceKind() {
        return type == TransferType.DEPOSIT ? AccountKind.CASH : AccountKind.CUSTOMER;
    }

    /** Para çekmede hedef kasa hesabı, diğer tiplerde müşteri hesabıdır. */
    public AccountKind destinationKind() {
        return type == TransferType.WITHDRAWAL ? AccountKind.CASH : AccountKind.CUSTOMER;
    }

    public void debited(Long fromCustomerId) {
        this.fromCustomerId = fromCustomerId;
        moveTo(SagaState.DEBITED);
    }

    public void credited(Long toCustomerId) {
        this.toCustomerId = toCustomerId;
        moveTo(SagaState.COMPLETED);
        this.completedAt = updatedAt;
    }

    public void beginCompensation(String code, String reason) {
        this.failureCode = code;
        this.failureReason = truncate(reason);
        moveTo(SagaState.COMPENSATING);
    }

    /** İlk adım reddedildi, hiç para hareket etmedi. */
    public void failed(String code, String reason, Long fromCustomerId) {
        this.failureCode = code;
        this.failureReason = truncate(reason);
        if (fromCustomerId != null) {
            this.fromCustomerId = fromCustomerId;
        }
        moveTo(SagaState.FAILED);
        this.completedAt = updatedAt;
    }

    public void compensated() {
        moveTo(SagaState.COMPENSATED);
        this.completedAt = updatedAt;
    }

    public void markReconciled() {
        this.reconciledAt = Instant.now();
    }

    /** Takılan adım yeniden gönderildi. */
    public void retried() {
        this.attempts++;
        Instant now = Instant.now();
        this.lastStepAt = now;
        this.updatedAt = now;
    }

    private void moveTo(SagaState next) {
        this.sagaState = next;
        this.attempts = 0;
        Instant now = Instant.now();
        this.lastStepAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }
}
