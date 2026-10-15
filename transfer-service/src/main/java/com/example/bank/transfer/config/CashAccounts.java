package com.example.bank.transfer.config;

import com.example.bank.common.domain.Currency;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Kasa hesaplarının id'leri (account-service V2 migration'ındaki sabit id'ler). */
@Component
public class CashAccounts {

    private final long tryId;
    private final long usdId;
    private final long eurId;

    public CashAccounts(@Value("${app.cash-accounts.try:1}") long tryId,
                        @Value("${app.cash-accounts.usd:2}") long usdId,
                        @Value("${app.cash-accounts.eur:3}") long eurId) {
        this.tryId = tryId;
        this.usdId = usdId;
        this.eurId = eurId;
    }

    public long idFor(Currency currency) {
        return switch (currency) {
            case TRY -> tryId;
            case USD -> usdId;
            case EUR -> eurId;
        };
    }

    public boolean isCash(Long accountId) {
        return accountId != null && (accountId == tryId || accountId == usdId || accountId == eurId);
    }

    public void requireNotCash(Long accountId) {
        if (isCash(accountId)) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + accountId);
        }
    }
}
