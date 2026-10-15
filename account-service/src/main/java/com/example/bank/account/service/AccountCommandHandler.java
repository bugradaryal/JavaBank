package com.example.bank.account.service;

import com.example.bank.account.domain.Account;
import com.example.bank.account.domain.LedgerEntry;
import com.example.bank.account.domain.OperationLog;
import com.example.bank.account.repository.AccountRepository;
import com.example.bank.account.repository.LedgerEntryRepository;
import com.example.bank.account.repository.OperationLogRepository;
import com.example.bank.common.domain.AccountKind;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import com.example.bank.common.outbox.OutboxService;
import com.example.bank.common.saga.AccountCommand;
import com.example.bank.common.saga.AccountReply;
import com.example.bank.common.saga.SagaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Saga komutlarını işler. Her komut TEK transaction'da: bakiye değişimi + ledger + operation_log + cevap (outbox).
 *
 * <p>Para hareketi her adımda hesap ile clearing hesabı arasındadır, böylece her commit'te Σ bakiye = 0:
 * <ul>
 *   <li>DEBIT_SOURCE: kaynak -> clearing</li>
 *   <li>CREDIT_DESTINATION: clearing -> hedef</li>
 *   <li>REFUND_SOURCE (telafi): clearing -> kaynak</li>
 * </ul>
 *
 * <p>Idempotency: aynı opKey ikinci kez gelirse bakiyeye dokunulmaz, kayıtlı sonuç yeniden cevaplanır.
 * İş kuralı reddi (yetersiz bakiye vb.) beklenen bir sonuçtur: bakiye değişmeden "success=false" cevabı gider.
 * Teknik hata (DB düştü vb.) exception olarak çıkar, transaction geri alınır, mesaj tekrar işlenir.
 */
@Service
public class AccountCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountCommandHandler.class);

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final OperationLogRepository operations;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final Tracer tracer;

    public AccountCommandHandler(AccountRepository accounts, LedgerEntryRepository ledger,
                                 OperationLogRepository operations, OutboxService outbox, MeterRegistry meters,
                                 Tracer tracer) {
        this.meters = meters;
        this.tracer = tracer;
        this.accounts = accounts;
        this.ledger = ledger;
        this.operations = operations;
        this.outbox = outbox;
    }

    /**
     * Metrikler: account.commands{step,result} (success / rejected / duplicate / error) ve süre.
     * Sayım commit'ten önce yapılır; commit'in kendisi başarısız olursa mesaj yeniden işlenir ve sonuç o zaman sayılır.
     */
    @Transactional
    public void handle(AccountCommand cmd) {
        Timer.Sample sample = Timer.start(meters);
        String result = "error";
        Span span = tracer.currentSpan();
        if (span != null) {
            span.tag("transfer.id", String.valueOf(cmd.transferId()));
            span.tag("saga.step", cmd.step().name());
        }
        try {
            result = process(cmd);
        } finally {
            if (span != null) {
                span.tag("command.result", result);
            }
            meters.counter("account.commands", "step", cmd.step().name(), "result", result).increment();
            sample.stop(Timer.builder("account.command.duration").tag("step", cmd.step().name())
                    .publishPercentileHistogram().register(meters));
        }
    }

    private String process(AccountCommand cmd) {
        String opKey = cmd.opKey();
        Optional<Account> clearingLookup = accounts.findByKindAndCurrency(AccountKind.CLEARING, cmd.currency());
        if (clearingLookup.isEmpty()) {
            throw new IllegalStateException("Clearing hesabı tanımlı değil: " + cmd.currency());
        }
        Long clearingId = clearingLookup.get().getId();

        // Kilitler id sırasıyla alınır: iki transaction ters sırada kilitleyip kilitlenmesin (deadlock).
        TreeSet<Long> ids = new TreeSet<>();
        ids.add(clearingId);
        ids.add(cmd.accountId());
        Account clearing = null;
        Account other = null;
        for (Long id : ids) {
            Optional<Account> locked = accounts.findByIdForUpdate(id);
            if (id.equals(clearingId)) {
                clearing = locked.orElseThrow();
            } else {
                other = locked.orElse(null);
            }
        }

        // Kilitten SONRA kontrol: aynı komutun iki kopyası yarışırsa ikincisi birincinin commit'ini görür.
        Optional<OperationLog> done = operations.findById(opKey);
        if (done.isPresent()) {
            log.info("Komut {} daha önce işlenmiş, kayıtlı sonuç tekrar gönderiliyor", opKey);
            OperationLog r = done.get();
            reply(cmd, r.isSuccess(), r.getReasonCode(), r.getReason(), r.getCustomerId());
            return "duplicate";
        }

        try {
            validate(cmd, other);
        } catch (BankException e) {
            // Sahiplik reddinde hesabın gerçek sahibi cevapta açıklanmaz (başkasına bildirim gitmesin).
            Long customerId = (other == null || e.getCode() == ErrorCode.ACCESS_DENIED) ? null : other.getCustomerId();
            operations.save(new OperationLog(opKey, false, e.getCode().name(), e.getMessage(), customerId));
            reply(cmd, false, e.getCode().name(), e.getMessage(), customerId);
            return "rejected";
        }

        apply(cmd, clearing, other, opKey);
        operations.save(new OperationLog(opKey, true, null, null, other.getCustomerId()));
        reply(cmd, true, null, null, other.getCustomerId());
        return "success";
    }

    private void validate(AccountCommand cmd, Account account) {
        if (account == null) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + cmd.accountId());
        }
        if (account.getKind() != cmd.expectedKind()) {
            throw new BankException(ErrorCode.VALIDATION_ERROR,
                    "Hesap türü uyuşmuyor: beklenen " + cmd.expectedKind() + ", gerçek " + account.getKind());
        }
        if (account.getCurrency() != cmd.currency()) {
            throw new BankException(ErrorCode.CURRENCY_MISMATCH, "Hesap para birimi " + account.getCurrency());
        }
        if (cmd.amount() == null || cmd.amount().signum() <= 0) {
            throw new BankException(ErrorCode.VALIDATION_ERROR, "Tutar pozitif olmalı");
        }
        switch (cmd.step()) {
            case DEBIT_SOURCE -> {
                if (cmd.ownerCustomerId() != null && !cmd.ownerCustomerId().equals(account.getCustomerId())) {
                    throw new BankException(ErrorCode.ACCESS_DENIED, "Bu hesaba erişimin yok");
                }
                if (!account.isActive()) {
                    throw new BankException(ErrorCode.ACCOUNT_CLOSED, "Hesap kapalı");
                }
                if (!account.canDebit(cmd.amount())) {
                    throw new BankException(ErrorCode.INSUFFICIENT_FUNDS, "Yetersiz bakiye");
                }
            }
            case CREDIT_DESTINATION -> {
                if (!account.isActive()) {
                    throw new BankException(ErrorCode.ACCOUNT_CLOSED, "Hedef hesap kapalı");
                }
            }
            case REFUND_SOURCE -> {
                // Telafi başarısız olamaz: kapanmış hesap bile iade alır (para zaten bankada, sahibine ait).
            }
        }
    }

    private void apply(AccountCommand cmd, Account clearing, Account other, String ref) {
        BigDecimal amount = cmd.amount();
        switch (cmd.step()) {
            case DEBIT_SOURCE -> move(other, clearing, amount, ref);
            case CREDIT_DESTINATION, REFUND_SOURCE -> move(clearing, other, amount, ref);
        }
    }

    private void move(Account from, Account to, BigDecimal amount, String ref) {
        from.debit(amount);
        to.credit(amount);
        ledger.save(LedgerEntry.debit(from.getId(), ref, amount, from.getBalance()));
        ledger.save(LedgerEntry.credit(to.getId(), ref, amount, to.getBalance()));
    }

    private void reply(AccountCommand cmd, boolean success, String code, String reason, Long customerId) {
        AccountReply reply = new AccountReply(UUID.randomUUID(), cmd.transferId(), cmd.step(), success, code, reason,
                customerId, Instant.now());
        outbox.enqueue(reply.messageId(), SagaTopics.ACCOUNT_REPLIES, "Transfer", String.valueOf(cmd.transferId()),
                "ACCOUNT_REPLY", reply);
    }
}
