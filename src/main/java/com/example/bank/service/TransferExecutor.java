package com.example.bank.service;

import com.example.bank.domain.Account;
import com.example.bank.domain.LedgerEntry;
import com.example.bank.domain.Transfer;
import com.example.bank.event.OutboxService;
import com.example.bank.error.BankException;
import com.example.bank.error.ErrorCode;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.LedgerEntryRepository;
import com.example.bank.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Para hareketinin tek transaction içinde yapıldığı yer.
 *
 * <p>TransferService bu sınıfı ayrı bir bean olarak çağırır. Sebep: @Transactional Spring'de proxy ile
 * çalışır, aynı sınıf içindeki bir metoddan çağrılan @Transactional metod proxy'den geçmez ve
 * transaction başlamaz. Ayrı bean olunca transaction sınırı net olur ve TransferService, transaction
 * geri alındıktan sonra (eşzamanlı aynı key durumunda) güvenle tekrar okuma yapabilir.
 */
@Service
public class TransferExecutor {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;
    private final AuditService audit;
    private final OutboxService outbox;

    public TransferExecutor(AccountRepository accounts, TransferRepository transfers,
                            LedgerEntryRepository ledger, AuditService audit, OutboxService outbox) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.audit = audit;
        this.outbox = outbox;
    }

    /**
     * Yetkilendirme çağıranda yapılmıştır. Burada: idempotency, kilit, kontroller, bakiye ve ledger.
     * İş kuralı hatası (hesap kapalı, kur uyuşmazlığı) exception ile tüm işlemi geri alır.
     * Yetersiz bakiye ise geri alma değil, FAILED durumunda kalıcı bir transfer kaydıdır.
     */
    @Transactional
    public TransferOutcome execute(TransferCommand cmd) {
        if (cmd.fromAccountId().equals(cmd.toAccountId())) {
            throw new BankException(ErrorCode.SAME_ACCOUNT, "Gönderen ve alıcı hesap aynı olamaz");
        }

        // 1) Kilit almadan hızlı yol: bu key daha önce işlendiyse eski sonucu dön.
        Optional<Transfer> existing = transfers.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }

        // 2) Deadlock'u önlemek için hesaplar HER ZAMAN küçük id'den büyüğe doğru kilitlenir.
        //    A->B ve B->A transferleri aynı anda gelse bile ikisi de önce küçük id'li hesabı ister;
        //    biri bekler, çapraz bekleme oluşmaz.
        Long firstId = Math.min(cmd.fromAccountId(), cmd.toAccountId());
        Long secondId = Math.max(cmd.fromAccountId(), cmd.toAccountId());
        Account first = lock(firstId);
        Account second = lock(secondId);
        Account from = first.getId().equals(cmd.fromAccountId()) ? first : second;
        Account to = from == first ? second : first;

        // 3) Kilidi beklerken aynı key'li başka bir istek bitmiş olabilir; kilit alındıktan sonra tekrar bak.
        existing = transfers.findByIdempotencyKey(cmd.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), cmd);
        }

        if (!from.isActive() || !to.isActive()) {
            throw new BankException(ErrorCode.ACCOUNT_CLOSED, "Kapalı hesaba işlem yapılamaz");
        }
        if (from.getCurrency() != to.getCurrency()) {
            throw new BankException(ErrorCode.CURRENCY_MISMATCH,
                    "Hesapların para birimi farklı: " + from.getCurrency() + " / " + to.getCurrency());
        }

        Transfer transfer = transfers.save(Transfer.pending(cmd.idempotencyKey(), from.getId(), to.getId(),
                cmd.amount(), from.getCurrency(), cmd.type(), cmd.description(), cmd.actor()));

        if (!from.canDebit(cmd.amount())) {
            transfer.fail("Yetersiz bakiye");
            audit.record(cmd.actor(), "TRANSFER_FAILED", "Transfer", transfer.getId(),
                    "reason=INSUFFICIENT_FUNDS, from=" + from.getId() + ", to=" + to.getId()
                            + ", amount=" + cmd.amount());
            outbox.enqueueTransferEvent(transfer, from, to);
            return new TransferOutcome(transfer, false);
        }

        // 4) Bakiye değişimi ve çift taraflı ledger kaydı aynı transaction'da.
        from.debit(cmd.amount());
        to.credit(cmd.amount());
        ledger.save(LedgerEntry.debit(from.getId(), transfer.getId(), cmd.amount(), from.getBalance()));
        ledger.save(LedgerEntry.credit(to.getId(), transfer.getId(), cmd.amount(), to.getBalance()));

        transfer.complete();
        audit.record(cmd.actor(), "TRANSFER_COMPLETED", "Transfer", transfer.getId(),
                "type=" + cmd.type() + ", from=" + from.getId() + ", to=" + to.getId()
                        + ", amount=" + cmd.amount() + " " + from.getCurrency());
        // Event, bakiye ve ledger ile AYNI transaction'da yazılır: para hareketi varsa event de vardır, yoksa ikisi de yoktur.
        outbox.enqueueTransferEvent(transfer, from, to);
        return new TransferOutcome(transfer, false);
    }

    private Account lock(Long id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Hesap bulunamadı: " + id));
    }

    /** Aynı key aynı içerikle gelirse eski sonucu dön; farklı içerikle gelirse hata ver. */
    private TransferOutcome replay(Transfer existing, TransferCommand cmd) {
        boolean samePayload = existing.getFromAccountId().equals(cmd.fromAccountId())
                && existing.getToAccountId().equals(cmd.toAccountId())
                && existing.getAmount().compareTo(cmd.amount()) == 0
                && existing.getType() == cmd.type();
        if (!samePayload) {
            throw new BankException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Bu Idempotency-Key farklı bir istekle daha önce kullanılmış");
        }
        return new TransferOutcome(existing, true);
    }
}
