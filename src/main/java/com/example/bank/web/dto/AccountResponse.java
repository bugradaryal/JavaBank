package com.example.bank.web.dto;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountStatus;
import com.example.bank.domain.Currency;

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
