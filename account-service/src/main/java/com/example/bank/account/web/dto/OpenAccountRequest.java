package com.example.bank.account.web.dto;

import com.example.bank.common.domain.Currency;
import jakarta.validation.constraints.NotNull;

/**
 * Müşteri kendi adına hesap açarken customerId göndermez (yok sayılır).
 * Gişe/admin başkası adına açarken customerId zorunludur.
 */
public record OpenAccountRequest(
        @NotNull Currency currency,
        Long customerId) {
}
