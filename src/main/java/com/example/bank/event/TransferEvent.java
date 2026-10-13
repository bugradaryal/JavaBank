package com.example.bank.event;

import com.example.bank.domain.Account;
import com.example.bank.domain.Currency;
import com.example.bank.domain.Transfer;
import com.example.bank.domain.TransferStatus;
import com.example.bank.domain.TransferType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kafka'ya giden transfer olayı. Consumer'lar sadece bu sözleşmeye bağlıdır, entity'lere değil.
 * fromCustomerId / toCustomerId, kasa (SYSTEM) hesaplarında null'dır.
 */
public record TransferEvent(
        UUID eventId,
        String eventType,
        Long transferId,
        TransferType type,
        TransferStatus status,
        Long fromAccountId,
        Long toAccountId,
        Long fromCustomerId,
        Long toCustomerId,
        BigDecimal amount,
        Currency currency,
        String failureReason,
        Instant occurredAt) {

    public static final String COMPLETED = "TRANSFER_COMPLETED";
    public static final String FAILED = "TRANSFER_FAILED";

    public static TransferEvent of(UUID eventId, Transfer transfer, Account from, Account to) {
        String eventType = switch (transfer.getStatus()) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case PENDING -> throw new IllegalStateException("PENDING durumundaki transfer için event üretilmez");
        };
        return new TransferEvent(eventId, eventType, transfer.getId(), transfer.getType(), transfer.getStatus(),
                from.getId(), to.getId(), from.getCustomerId(), to.getCustomerId(), transfer.getAmount(),
                transfer.getCurrency(), transfer.getFailureReason(), Instant.now());
    }
}
