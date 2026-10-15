package com.example.bank.account.web.dto;

import com.example.bank.account.domain.EntryType;
import com.example.bank.account.domain.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        Long id,
        Long accountId,
        String reference,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(e.getId(), e.getAccountId(), e.getReference(), e.getEntryType(),
                e.getAmount(), e.getBalanceAfter(), e.getCreatedAt());
    }
}
