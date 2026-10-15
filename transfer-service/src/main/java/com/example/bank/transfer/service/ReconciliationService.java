package com.example.bank.transfer.service;

import com.example.bank.transfer.client.AccountClient;
import com.example.bank.transfer.client.AccountClient.OpResult;
import com.example.bank.transfer.domain.ReconciliationFinding;
import com.example.bank.transfer.domain.Transfer;
import com.example.bank.transfer.repository.ReconciliationFindingRepository;
import com.example.bank.transfer.repository.TransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bitmiş transferleri account-service'in işlem kayıtlarıyla karşılaştırır (servisler arası mutabakat).
 * HTTP çağrısı transaction DIŞINDA yapılır; DB'ye yalnızca kısa yazma transaction'ları girer.
 * Her transfer bir kez kontrol edilir (reconciled_at); uyuşmazlık "finding" olarak kalıcı yazılır, insan kapatana kadar açık kalır.
 * account-service erişilemezse bu tur atlanır, transferler işaretlenmez ve sonraki turda tekrar denenir.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record Result(int checked, int mismatches, int batches) {
    }

    private final TransferRepository transfers;
    private final ReconciliationFindingRepository findings;
    private final AccountClient accountClient;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Duration grace;
    private final int batchSize;
    private final int maxBatches;

    public ReconciliationService(TransferRepository transfers, ReconciliationFindingRepository findings,
                                 AccountClient accountClient, TransactionTemplate tx, MeterRegistry meters,
                                 @Value("${app.reconciliation.grace-ms:30000}") long graceMs,
                                 @Value("${app.reconciliation.batch-size:100}") int batchSize,
                                 @Value("${app.reconciliation.max-batches:20}") int maxBatches) {
        this.transfers = transfers;
        this.findings = findings;
        this.accountClient = accountClient;
        this.tx = tx;
        this.meters = meters;
        this.grace = Duration.ofMillis(graceMs);
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    /** Bekleyen transferleri parça parça kontrol eder. account-service hatası exception olarak çıkar. */
    public Result runOnce() {
        int checked = 0;
        int mismatches = 0;
        int batches = 0;
        while (batches < maxBatches) {
            List<Transfer> batch = transfers.findUnreconciled(Instant.now().minus(grace), PageRequest.of(0, batchSize));
            if (batch.isEmpty()) {
                break;
            }
            List<String> keys = new ArrayList<>();
            batch.forEach(t -> keys.addAll(ReconciliationEvaluator.keysFor(t)));
            Map<String, OpResult> ops = accountClient.lookup(keys);

            for (Transfer t : batch) {
                Optional<ReconciliationEvaluator.Mismatch> mismatch = ReconciliationEvaluator.evaluate(t, ops);
                tx.executeWithoutResult(status -> {
                    transfers.findById(t.getId()).ifPresent(fresh -> {
                        fresh.markReconciled();
                        transfers.save(fresh);
                    });
                    mismatch.ifPresent(m -> findings.save(new ReconciliationFinding(t.getId(), m.kind(), m.details())));
                });
                mismatch.ifPresent(m -> {
                    meters.counter("reconciliation.mismatches", "kind", m.kind()).increment();
                    log.error("MUTABAKAT UYUŞMAZLIĞI [{}] {}", m.kind(), m.details());
                });
                if (mismatch.isPresent()) {
                    mismatches++;
                }
                checked++;
            }
            batches++;
        }
        meters.counter("reconciliation.transfers.checked").increment(checked);
        return new Result(checked, mismatches, batches);
    }
}
