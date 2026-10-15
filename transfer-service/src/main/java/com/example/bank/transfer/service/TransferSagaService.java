package com.example.bank.transfer.service;

import com.example.bank.common.events.TransferEvent;
import com.example.bank.common.outbox.OutboxService;
import com.example.bank.common.saga.AccountCommand;
import com.example.bank.common.saga.AccountReply;
import com.example.bank.common.saga.SagaStep;
import com.example.bank.common.saga.SagaTopics;
import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferType;
import com.example.bank.transfer.domain.Transfer;
import com.example.bank.transfer.repository.TransferRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Saga ORKESTRATÖRÜ. Neden orkestrasyon (koreografi değil): adım sırası ve telafi kuralları tek yerde yazılı,
 * "şu transfer şu an nerede?" sorusunun cevabı tek satırda (saga_state), takılan saga'yı bulup kurtarmak kolay.
 * Koreografide bu bilgi servislerin olaylarına dağılır ve akışı anlamak/izlemek zorlaşır; para gibi kritik
 * bir akışta görünürlük ve denetlenebilirlik hız kazancından önemli.
 *
 * <p>Her metot TEK transaction'dır: durum değişimi + gidecek mesaj (outbox) birlikte commit olur.
 * Servis herhangi bir noktada çökerse ya hiçbir şey yazılmamıştır ya da hem durum hem mesaj yazılmıştır.
 * Mesaj kaybolursa/çift gelirse {@link #recoverStuck} ve account-service'in idempotency'si devreye girer.
 */
@Service
public class TransferSagaService {

    private static final Logger log = LoggerFactory.getLogger(TransferSagaService.class);
    private static final String AGGREGATE = "Transfer";

    public record StartCommand(String idempotencyKey, Long fromAccountId, Long toAccountId, BigDecimal amount,
                               Currency currency, TransferType type, String description,
                               String initiatedBy, Long ownerCustomerId) {
    }

    private final TransferRepository transfers;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final Tracer tracer;

    public TransferSagaService(TransferRepository transfers, OutboxService outbox, MeterRegistry meters,
                               Tracer tracer) {
        this.transfers = transfers;
        this.outbox = outbox;
        this.meters = meters;
        this.tracer = tracer;
    }

    /** Trace'i iş anahtarıyla etiketler: Tempo'da "{ span.transfer.id = 42 }" ile bir transferin tüm izi bulunur. */
    private void tag(Transfer t) {
        Span span = tracer.currentSpan();
        if (span != null && t.getId() != null) {
            span.tag("transfer.id", String.valueOf(t.getId()));
            span.tag("saga.state", t.getSagaState().name());
        }
    }

    @Transactional(readOnly = true)
    public Optional<Transfer> findReplay(String idempotencyKey) {
        return transfers.findByIdempotencyKey(idempotencyKey);
    }

    /** Transfer kaydı + ilk komut (kaynaktan düş) aynı transaction'da. Aynı key ikinci kez eklenemez (unique). */
    @Transactional
    public Transfer start(StartCommand c) {
        Transfer t = transfers.saveAndFlush(Transfer.start(c.idempotencyKey(), c.fromAccountId(), c.toAccountId(),
                c.amount(), c.currency(), c.type(), c.description(), c.initiatedBy(), c.ownerCustomerId()));
        tag(t);
        sendCommand(t, SagaStep.DEBIT_SOURCE);
        meters.counter("saga.started", "type", t.getType().name()).increment();
        return t;
    }

    /**
     * account-service cevabı. Bilinmeyen, tekrar eden veya eski bir cevap (state o adımı çoktan geçmişse)
     * sessizce yok sayılır: state machine sadece beklenen geçişi kabul eder.
     */
    @Transactional
    public void onReply(AccountReply reply) {
        Optional<Transfer> found = transfers.findByIdForUpdate(reply.transferId());
        if (found.isEmpty()) {
            log.warn("Bilinmeyen transfer için cevap: {}", reply.transferId());
            return;
        }
        Transfer t = found.get();
        tag(t);
        switch (t.getSagaState()) {
            case STARTED -> {
                if (reply.step() != SagaStep.DEBIT_SOURCE) {
                    ignore(t, reply);
                } else if (reply.success()) {
                    t.debited(reply.customerId());
                    sendCommand(t, SagaStep.CREDIT_DESTINATION);
                } else {
                    t.failed(reply.reasonCode(), reply.reason(), reply.customerId());
                    publishResult(t);
                }
            }
            case DEBITED -> {
                if (reply.step() != SagaStep.CREDIT_DESTINATION) {
                    ignore(t, reply);
                } else if (reply.success()) {
                    t.credited(reply.customerId());
                    publishResult(t);
                } else {
                    // Para kaynaktan çıktı ama hedefe geçemedi: telafi = clearing'deki parayı kaynağa iade et.
                    t.beginCompensation(reply.reasonCode(), reply.reason());
                    sendCommand(t, SagaStep.REFUND_SOURCE);
                }
            }
            case COMPENSATING -> {
                if (reply.step() != SagaStep.REFUND_SOURCE) {
                    ignore(t, reply);
                } else if (reply.success()) {
                    t.compensated();
                    publishResult(t);
                } else {
                    // İade reddedilmemeli (account-service telafiyi her durumda kabul eder). Olursa insan müdahalesi
                    // gerekir; durum COMPENSATING kalır, recovery komutu tekrar gönderir ve log'da görünür.
                    log.error("Transfer {} telafisi reddedildi: {} {}", t.getId(), reply.reasonCode(), reply.reason());
                }
            }
            default -> ignore(t, reply);
        }
    }

    /**
     * Takılan saga'ları kurtarır: son adımı {@code cutoff}'tan eski ve bitmemiş olanların MEVCUT adım komutu
     * yeniden gönderilir. Bu güvenlidir çünkü account-service komutları opKey ile idempotent işler.
     *
     * @return yeniden gönderilen komut sayısı
     */
    @Transactional
    public int recoverStuck(Instant cutoff, int limit) {
        List<Transfer> stuck = transfers.lockStuck(cutoff, limit);
        for (Transfer t : stuck) {
            SagaStep step = switch (t.getSagaState()) {
                case STARTED -> SagaStep.DEBIT_SOURCE;
                case DEBITED -> SagaStep.CREDIT_DESTINATION;
                case COMPENSATING -> SagaStep.REFUND_SOURCE;
                default -> null;
            };
            if (step == null) {
                continue;
            }
            t.retried();
            meters.counter("saga.step.retries", "step", step.name()).increment();
            log.warn("Transfer {} {} durumunda takılmış (deneme {}), {} yeniden gönderiliyor",
                    t.getId(), t.getSagaState(), t.getAttempts(), step);
            sendCommand(t, step);
        }
        return stuck.size();
    }

    private void sendCommand(Transfer t, SagaStep step) {
        AccountCommand cmd = switch (step) {
            case DEBIT_SOURCE -> new AccountCommand(UUID.randomUUID(), t.getId(), step, t.getFromAccountId(),
                    t.getAmount(), t.getCurrency(), t.sourceKind(), t.getOwnerCustomerId(), t.getType());
            case CREDIT_DESTINATION -> new AccountCommand(UUID.randomUUID(), t.getId(), step, t.getToAccountId(),
                    t.getAmount(), t.getCurrency(), t.destinationKind(), null, t.getType());
            // Telafi: sahiplik kontrolü yok, hesap kapalı olsa da iade edilir.
            case REFUND_SOURCE -> new AccountCommand(UUID.randomUUID(), t.getId(), step, t.getFromAccountId(),
                    t.getAmount(), t.getCurrency(), t.sourceKind(), null, t.getType());
        };
        outbox.enqueue(cmd.messageId(), SagaTopics.commandTopic(step), AGGREGATE, String.valueOf(t.getId()),
                step.name(), cmd);
    }

    private void publishResult(Transfer t) {
        boolean ok = t.getSagaState() == com.example.bank.transfer.domain.SagaState.COMPLETED;
        // Sonuç sayacı ve uçtan uca süre (transferin oluşturulmasından sonuçlanmasına kadar).
        String outcome = t.getSagaState().name().toLowerCase();
        meters.counter("saga.completed", "outcome", outcome, "type", t.getType().name()).increment();
        Timer.builder("saga.duration").tag("outcome", outcome).publishPercentileHistogram().register(meters)
                .record(java.time.Duration.between(t.getCreatedAt(), Instant.now()));
        TransferEvent event = new TransferEvent(UUID.randomUUID(),
                ok ? TransferEvent.COMPLETED : TransferEvent.FAILED,
                t.getId(), t.getType(), t.status(), t.getFromAccountId(), t.getToAccountId(),
                t.getFromCustomerId(), t.getToCustomerId(), t.getAmount(), t.getCurrency(),
                ok ? null : t.getFailureReason(), Instant.now());
        outbox.enqueue(event.eventId(), SagaTopics.TRANSFER_EVENTS, AGGREGATE, String.valueOf(t.getId()),
                event.eventType(), event);
    }

    private void ignore(Transfer t, AccountReply reply) {
        log.info("Transfer {} ({}) için beklenmeyen/tekrar cevap yok sayıldı: {} success={}",
                t.getId(), t.getSagaState(), reply.step(), reply.success());
    }
}
