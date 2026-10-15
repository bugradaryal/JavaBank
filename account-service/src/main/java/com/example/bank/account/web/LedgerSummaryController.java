package com.example.bank.account.web;

import com.example.bank.account.domain.Account;
import com.example.bank.account.repository.AccountRepository;
import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.domain.Currency;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Muhasebe sağlık kontrolü. totalBalance (kasa + clearing + müşteriler) HER ZAMAN 0 olmalı;
 * clearingBalance saga'lar bittiğinde 0'a döner (sıfırdan farklıysa yolda para var demektir).
 */
@RestController
@RequestMapping("/api/admin/ledger-summary")
public class LedgerSummaryController {

    public record SystemAccount(Long id, AccountKind kind, Currency currency, BigDecimal balance) {
    }

    public record Summary(BigDecimal totalBalance, BigDecimal clearingBalance, List<SystemAccount> systemAccounts) {
    }

    private final AccountRepository accounts;

    public LedgerSummaryController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Summary summary() {
        List<Account> system = accounts.findByKindIn(List.of(AccountKind.CASH, AccountKind.CLEARING));
        BigDecimal clearing = system.stream().filter(a -> a.getKind() == AccountKind.CLEARING)
                .map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(accounts.totalBalance(), clearing, system.stream()
                .map(a -> new SystemAccount(a.getId(), a.getKind(), a.getCurrency(), a.getBalance())).toList());
    }
}
