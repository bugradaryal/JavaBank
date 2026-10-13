package com.example.bank.service;

import com.example.bank.domain.TransferType;

import java.math.BigDecimal;

public record TransferCommand(
        String idempotencyKey,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        TransferType type,
        String description,
        String actor) {
}
