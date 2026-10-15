package com.example.bank.transfer.service;

import com.example.bank.common.domain.TransferType;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import com.example.bank.common.security.AuthUser;
import com.example.bank.transfer.config.CashAccounts;
import com.example.bank.transfer.domain.Transfer;
import com.example.bank.transfer.repository.TransferRepository;
import com.example.bank.transfer.web.dto.CashRequest;
import com.example.bank.transfer.web.dto.TransferRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Giriş noktası: girdi/yetki kontrolü ve idempotency. Bilerek @Transactional DEĞİL: saga başlatan transaction
 * unique ihlaliyle geri alındıktan sonra catch bloğunda yeni okuma yapabilmek için.
 * Transfer asenkron yürür: burası saga'yı başlatıp PENDING döner, sonuç GET ile okunur.
 */
@Service
public class TransferService {

    private static final int MAX_KEY_LENGTH = 100;
    private static final Set<TransferType> CUSTOMER_FACING_TYPES =
            Set.of(TransferType.EFT, TransferType.BILL_PAYMENT, TransferType.CARD_PAYMENT);

    private final TransferSagaService saga;
    private final TransferRepository transfers;
    private final CashAccounts cash;

    public TransferService(TransferSagaService saga, TransferRepository transfers, CashAccounts cash) {
        this.saga = saga;
        this.transfers = transfers;
        this.cash = cash;
    }

    public TransferOutcome transfer(AuthUser actor, TransferRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        if (!CUSTOMER_FACING_TYPES.contains(req.type())) {
            throw new BankException(ErrorCode.INVALID_TRANSFER_TYPE,
                    "Bu endpoint sadece EFT, BILL_PAYMENT ve CARD_PAYMENT kabul eder");
        }
        if (req.fromAccountId().equals(req.toAccountId())) {
            throw new BankException(ErrorCode.SAME_ACCOUNT, "Kaynak ve hedef hesap aynı olamaz");
        }
        cash.requireNotCash(req.fromAccountId());
        cash.requireNotCash(req.toAccountId());
        // Hesabın gerçekten bu müşteriye ait olduğunu account-service doğrular (ownerCustomerId komutla gider).
        Long owner = ownerOf(actor);
        return run(new TransferSagaService.StartCommand(key, req.fromAccountId(), req.toAccountId(), req.amount(),
                req.currency(), req.type(), req.description(), actor.username(), owner));
    }

    /** Gişeden para yatırma: kasadan müşteri hesabına. Sadece personel. */
    public TransferOutcome deposit(AuthUser actor, CashRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        requireStaff(actor);
        cash.requireNotCash(req.accountId());
        return run(new TransferSagaService.StartCommand(key, cash.idFor(req.currency()), req.accountId(),
                req.amount(), req.currency(), TransferType.DEPOSIT, req.description(), actor.username(), null));
    }

    /** Gişeden para çekme: müşteri hesabından kasaya. Sadece personel. */
    public TransferOutcome withdraw(AuthUser actor, CashRequest req, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        requireStaff(actor);
        cash.requireNotCash(req.accountId());
        return run(new TransferSagaService.StartCommand(key, req.accountId(), cash.idFor(req.currency()),
                req.amount(), req.currency(), TransferType.WITHDRAWAL, req.description(), actor.username(), null));
    }

    public Transfer get(Long id, AuthUser actor) {
        Transfer t = transfers.findById(id)
                .orElseThrow(() -> new BankException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer bulunamadı: " + id));
        if (!actor.isStaff()) {
            boolean owns = Objects.equals(t.getOwnerCustomerId(), actor.customerId())
                    || Objects.equals(t.getToCustomerId(), actor.customerId());
            if (!owns) {
                throw new BankException(ErrorCode.ACCESS_DENIED, "Bu transfere erişimin yok");
            }
        }
        return t;
    }

    /** wait: sonuçlanmasını en fazla bu kadar ms bekle (kısa polling). Sonuçlanmadıysa PENDING döner. */
    public Transfer awaitResult(Transfer t, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.min(Math.max(waitMs, 0), 10_000);
        Transfer current = t;
        while (!current.getSagaState().isTerminal() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            current = transfers.findById(t.getId()).orElse(current);
        }
        return current;
    }

    private TransferOutcome run(TransferSagaService.StartCommand cmd) {
        Optional<Transfer> existing = saga.findReplay(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }
        try {
            return new TransferOutcome(saga.start(cmd), false);
        } catch (DataIntegrityViolationException e) {
            // Aynı key'li iki istek aynı anda araya girdi; unique constraint ikincisini reddetti, hiçbir şey yazılmadı.
            return saga.findReplay(cmd.idempotencyKey())
                    .map(t -> replay(t, cmd))
                    .orElseThrow(() -> e);
        }
    }

    private static TransferOutcome replay(Transfer t, TransferSagaService.StartCommand cmd) {
        boolean same = t.getFromAccountId().equals(cmd.fromAccountId())
                && t.getToAccountId().equals(cmd.toAccountId())
                && t.getAmount().compareTo(cmd.amount()) == 0
                && t.getCurrency() == cmd.currency()
                && t.getType() == cmd.type();
        if (!same) {
            throw new BankException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Bu Idempotency-Key farklı bir istekle daha önce kullanılmış");
        }
        return new TransferOutcome(t, true);
    }

    private static Long ownerOf(AuthUser actor) {
        if (actor.isStaff()) {
            return null;
        }
        if (actor.customerId() == null) {
            throw new BankException(ErrorCode.ACCESS_DENIED, "Müşteri kaydı olmayan kullanıcı transfer yapamaz");
        }
        return actor.customerId();
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
}
