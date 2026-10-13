package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountKind;
import com.example.bank.error.BankException;
import com.example.bank.error.ErrorCode;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
import com.example.bank.repository.LedgerEntryRepository;
import com.example.bank.security.AuthUser;
import com.example.bank.web.dto.AccountResponse;
import com.example.bank.web.dto.LedgerEntryResponse;
import com.example.bank.web.dto.OpenAccountRequest;
import com.example.bank.web.dto.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final CustomerRepository customers;
    private final LedgerEntryRepository ledger;
    private final IbanGenerator ibanGenerator;
    private final AuditService audit;

    public AccountService(AccountRepository accounts, CustomerRepository customers, LedgerEntryRepository ledger,
                          IbanGenerator ibanGenerator, AuditService audit) {
        this.accounts = accounts;
        this.customers = customers;
        this.ledger = ledger;
        this.ibanGenerator = ibanGenerator;
        this.audit = audit;
    }

    @Transactional
    public AccountResponse open(OpenAccountRequest req, AuthUser actor) {
        Long customerId;
        if (actor.isStaff()) {
            if (req.customerId() == null) {
                throw new BankException(ErrorCode.VALIDATION_ERROR, "customerId zorunlu");
            }
            customerId = req.customerId();
        } else {
            customerId = actor.customerId();
        }
        if (!customers.existsById(customerId)) {
            throw new BankException(ErrorCode.CUSTOMER_NOT_FOUND, "Müşteri bulunamadı: " + customerId);
        }
        String iban = ibanGenerator.generate(accounts.nextAccountNumber());
        Account account = accounts.save(Account.open(iban, customerId, req.currency()));
        audit.record(actor.username(), "ACCOUNT_OPENED", "Account", account.getId(),
                "iban=" + iban + ", currency=" + req.currency());
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Long id, AuthUser actor) {
        Account account = find(id);
        authorize(account, actor);
        return AccountResponse.from(account);
    }

    /** Müşteri sadece kendi hesaplarını görür. Gişe/admin customerId verirse o müşterinin, vermezse tüm hesapları. */
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> list(Long customerId, AuthUser actor, Pageable pageable) {
        if (!actor.isStaff()) {
            return PageResponse.from(accounts.findByCustomerId(actor.customerId(), pageable),
                    AccountResponse::from);
        }
        if (customerId != null) {
            return PageResponse.from(accounts.findByCustomerId(customerId, pageable), AccountResponse::from);
        }
        return PageResponse.from(accounts.findByKind(AccountKind.CUSTOMER, pageable), AccountResponse::from);
    }

    @Transactional
    public AccountResponse close(Long id, AuthUser actor) {
        // Bakiye kontrolü ile kapatma arasında para hareketi olmasın diye kilitleyerek okunur.
        Account account = accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + id));
        authorize(account, actor);
        account.close();
        audit.record(actor.username(), "ACCOUNT_CLOSED", "Account", id, null);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> ledger(Long id, AuthUser actor, Pageable pageable) {
        Account account = find(id);
        authorize(account, actor);
        return PageResponse.from(ledger.findByAccountIdOrderByIdDesc(id, pageable), LedgerEntryResponse::from);
    }

    private Account find(Long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + id));
    }

    private void authorize(Account account, AuthUser actor) {
        if (actor.isStaff()) {
            return;
        }
        if (account.getKind() == AccountKind.SYSTEM || !Objects.equals(account.getCustomerId(), actor.customerId())) {
            throw new BankException(ErrorCode.ACCESS_DENIED, "Bu hesaba erişimin yok");
        }
    }
}
