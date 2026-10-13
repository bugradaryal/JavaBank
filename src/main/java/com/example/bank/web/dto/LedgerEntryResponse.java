package com.example.bank.web.dto;

import com.example.bank.domain.EntryType;
import com.example.bank.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        Long id,
        Long accountId,
        Long transferId,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(e.getId(), e.getAccountId(), e.getTransferId(), e.getEntryType(),
                e.getAmount(), e.getBalanceAfter(), e.getCreatedAt());
    }
}
