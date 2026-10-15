package com.example.bank.account.service;

import com.example.bank.account.domain.Account;
import com.example.bank.account.repository.AccountRepository;
import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Muhasebe değişmezleri metrik olarak:
 * ledger.total.balance (tüm bakiyelerin toplamı, HER ZAMAN 0 olmalı),
 * ledger.clearing.balance{currency} (yoldaki para; saga'lar bitince 0).
 * İkisi de alarm kuralına bağlıdır. Hata olursa NaN döner.
 */
@Component
public class LedgerMetrics implements MeterBinder {

    private final AccountRepository accounts;

    public LedgerMetrics(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("ledger.total.balance", this, m -> m.total())
                .description("Tüm hesap bakiyelerinin toplamı (kasa ve clearing dahil), 0 olmalı").register(registry);
        for (Currency currency : Currency.values()) {
            Gauge.builder("ledger.clearing.balance", this, m -> m.clearing(currency))
                    .tag("currency", currency.name())
                    .description("Clearing (askı) hesabındaki yoldaki para").register(registry);
        }
    }

    private double total() {
        try {
            return accounts.totalBalance().doubleValue();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double clearing(Currency currency) {
        try {
            return accounts.findByKindAndCurrency(AccountKind.CLEARING, currency)
                    .map(Account::getBalance).map(b -> b.doubleValue()).orElse(0.0);
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
