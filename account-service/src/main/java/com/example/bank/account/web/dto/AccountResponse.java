package com.example.bank.account.web.dto;

import com.example.bank.account.domain.Account;
import com.example.bank.account.domain.AccountStatus;
import com.example.bank.common.domain.Currency;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String iban,
        Long customerId,
        Currency currency,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getId(), a.getIban(), a.getCustomerId(), a.getCurrency(),
                a.getBalance(), a.getStatus(), a.getCreatedAt());
    }
}
