package com.example.bank.common.events;

import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.domain.TransferType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Transfer sonuçlandığında (COMPLETED veya FAILED) transfer-service'in yayınladığı olay.
 * Consumer'lar sadece bu sözleşmeye bağlıdır, transfer-service'in entity'lerine değil.
 * fromCustomerId / toCustomerId kasa hesaplarında ve bilinmeyen durumlarda null'dır.
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
}
