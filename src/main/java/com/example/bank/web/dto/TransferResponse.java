package com.example.bank.web.dto;

import com.example.bank.domain.Currency;
import com.example.bank.domain.Transfer;
import com.example.bank.domain.TransferStatus;
import com.example.bank.domain.TransferType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        Long id,
        String idempotencyKey,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        Currency currency,
        TransferType type,
        TransferStatus status,
        String description,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getId(), t.getIdempotencyKey(), t.getFromAccountId(), t.getToAccountId(),
                t.getAmount(), t.getCurrency(), t.getType(), t.getStatus(), t.getDescription(),
                t.getFailureReason(), t.getCreatedAt(), t.getCompletedAt());
    }
}
