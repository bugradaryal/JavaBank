package com.example.bank.transfer.web.dto;

import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferStatus;
import com.example.bank.common.domain.TransferType;
import com.example.bank.transfer.domain.SagaState;
import com.example.bank.transfer.domain.Transfer;

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
        SagaState sagaState,
        String description,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant completedAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(t.getId(), t.getIdempotencyKey(), t.getFromAccountId(), t.getToAccountId(),
                t.getAmount(), t.getCurrency(), t.getType(), t.status(), t.getSagaState(), t.getDescription(),
                t.getFailureCode(), t.getFailureReason(), t.getCreatedAt(), t.getCompletedAt());
    }
}
