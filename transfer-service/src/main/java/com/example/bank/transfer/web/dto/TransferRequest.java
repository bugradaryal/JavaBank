package com.example.bank.transfer.web.dto;

import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Para birimi istekte açıkça gelir: transfer-service hesapları bilmez, uyuşmazlığı account-service reddeder. */
public record TransferRequest(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull Currency currency,
        @NotNull TransferType type,
        @Size(max = 255) String description) {
}
