package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.AccountKind;
import com.example.bank.domain.Transfer;
import com.example.bank.domain.TransferType;
import com.example.bank.error.BankException;
import com.example.bank.error.ErrorCode;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.TransferRepository;
import com.example.bank.security.AuthUser;
import com.example.bank.web.dto.MoneyRequest;
import com.example.bank.web.dto.TransferRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * Transfer giriş noktası: yetkilendirme ve girdi kontrolü burada, para hareketi TransferExecutor'da.
 * Bu sınıf bilerek @Transactional DEĞİL; executor'ın transaction'ı bitip geri alındıktan sonra
 * catch bloğunda yeni okuma yapabilmek için.
 */
@Service
public class TransferService {

    private static final int MAX_KEY_LENGTH = 100;
    private static final Set<TransferType> CUSTOMER_FACING_TYPES =
            Set.of(TransferType.EFT, TransferType.BILL_PAYMENT, TransferType.CARD_PAYMENT);

    private final TransferExecutor executor;
    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public TransferService(TransferExecutor executor, AccountRepository accounts, TransferRepository transfers) {
        this.executor = executor;
        this.accounts = accounts;
        this.transfers = transfers;
    }

    /** Müşteri veya gişe tarafından hesaplar arası transfer / fatura / kart ödemesi. */
    public TransferOutcome transfer(AuthUser actor, TransferRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        if (!CUSTOMER_FACING_TYPES.contains(req.type())) {
            throw new BankException(ErrorCode.INVALID_TRANSFER_TYPE,
                    "Bu endpoint sadece EFT, BILL_PAYMENT ve CARD_PAYMENT kabul eder");
        }
        Account from = find(req.fromAccountId());
        Account to = find(req.toAccountId());
        if (from.getKind() == AccountKind.SYSTEM || to.getKind() == AccountKind.SYSTEM) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı");
        }
        if (!actor.isStaff() && !Objects.equals(from.getCustomerId(), actor.customerId())) {
            throw new BankException(ErrorCode.ACCESS_DENIED, "Sadece kendi hesabından transfer yapabilirsin");
        }
        return run(new TransferCommand(key, from.getId(), to.getId(), req.amount(), req.type(),
                req.description(), actor.username()));
    }

    /** Gişeden para yatırma: kasa hesabından müşteri hesabına. Sadece personel. */
    public TransferOutcome deposit(AuthUser actor, Long accountId, MoneyRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        requireStaff(actor);
        Account target = findCustomerAccount(accountId);
        Account cash = findCashAccount(target);
        return run(new TransferCommand(key, cash.getId(), target.getId(), req.amount(), TransferType.DEPOSIT,
                req.description(), actor.username()));
    }

    /** Gişeden para çekme: müşteri hesabından kasa hesabına. Sadece personel. */
    public TransferOutcome withdraw(AuthUser actor, Long accountId, MoneyRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        requireStaff(actor);
        Account source = findCustomerAccount(accountId);
        Account cash = findCashAccount(source);
        return run(new TransferCommand(key, source.getId(), cash.getId(), req.amount(), TransferType.WITHDRAWAL,
                req.description(), actor.username()));
    }

    @Transactional(readOnly = true)
    public Transfer get(Long id, AuthUser actor) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> new BankException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer bulunamadı: " + id));
        if (!actor.isStaff()) {
            Account from = find(t.getFromAccountId());
            Account to = find(t.getToAccountId());
            boolean owns = Objects.equals(from.getCustomerId(), actor.customerId())
                    || Objects.equals(to.getCustomerId(), actor.customerId());
            if (!owns) {
                throw new BankException(ErrorCode.ACCESS_DENIED, "Bu transfere erişimin yok");
            }
        }
        return t;
    }

    private TransferOutcome run(TransferCommand cmd) {
        try {
            return executor.execute(cmd);
        } catch (DataIntegrityViolationException e) {
            // Aynı key'li iki istek aynı anda araya girdiyse DB'deki unique constraint ikincisini reddeder.
            // O transaction geri alındı (para hareket etmedi); kazanan isteğin sonucunu okuyup dön.
            return transfers.findByIdempotencyKey(cmd.idempotencyKey())
                    .map(existing -> sameAs(existing, cmd)
                            ? new TransferOutcome(existing, true)
                            : failReused())
                    .orElseThrow(() -> e);
        }
    }

    private static boolean sameAs(Transfer t, TransferCommand cmd) {
        return t.getFromAccountId().equals(cmd.fromAccountId())
                && t.getToAccountId().equals(cmd.toAccountId())
                && t.getAmount().compareTo(cmd.amount()) == 0
                && t.getType() == cmd.type();
    }

    private static TransferOutcome failReused() {
        throw new BankException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                "Bu Idempotency-Key farklı bir istekle daha önce kullanılmış");
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BankException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "Idempotency-Key header'ı zorunlu");
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw new BankException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key en fazla " + MAX_KEY_LENGTH + " karakter olabilir");
        }
        return trimmed;
    }

    private static void requireStaff(AuthUser actor) {
        if (!actor.isStaff()) {
            throw new BankException(ErrorCode.ACCESS_DENIED, "Bu işlem sadece personel içindir");
        }
    }

    private Account find(Long id) {
        return accounts.findById(id)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + id));
    }

    private Account findCustomerAccount(Long id) {
        Account a = find(id);
        if (a.getKind() == AccountKind.SYSTEM) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + id);
        }
        return a;
    }

    private Account findCashAccount(Account customerAccount) {
        return accounts.findByKindAndCurrency(AccountKind.SYSTEM, customerAccount.getCurrency())
                .orElseThrow(() -> new BankException(ErrorCode.INTERNAL_ERROR,
                        "Kasa hesabı bulunamadı: " + customerAccount.getCurrency()));
    }
}
