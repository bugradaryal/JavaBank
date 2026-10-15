package com.example.bank.transfer.web.dto;

import com.example.bank.common.domain.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Para yatırma / çekme isteği (gişe). */
public record CashRequest(
        @NotNull Long accountId,
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull Currency currency,
        @Size(max = 255) String description) {
}
