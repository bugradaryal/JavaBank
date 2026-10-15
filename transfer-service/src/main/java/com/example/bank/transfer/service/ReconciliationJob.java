package com.example.bank.transfer.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.example.bank.transfer.repository.ReconciliationFindingRepository;
import com.example.bank.transfer.repository.TransferRepository;

@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;
    private final MeterRegistry meters;

    public ReconciliationJob(ReconciliationService service, MeterRegistry meters,
                             ReconciliationFindingRepository findings, TransferRepository transfers) {
        this.service = service;
        this.meters = meters;
        Gauge.builder("reconciliation.findings.open", findings, f -> safe(f::countByResolvedAtIsNull))
                .description("Açık (çözülmemiş) mutabakat bulgusu sayısı, 0 olmalı").register(meters);
        Gauge.builder("reconciliation.unreconciled", transfers, t -> safe(t::countUnreconciled))
                .description("Bitmiş ama henüz mutabakatı yapılmamış transfer sayısı").register(meters);
    }

    private static double safe(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    @Scheduled(initialDelayString = "${app.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${app.reconciliation.interval-ms:60000}")
    public void run() {
        try {
            ReconciliationService.Result r = service.runOnce();
            meters.counter("reconciliation.runs", "result", r.mismatches() == 0 ? "ok" : "mismatches").increment();
            if (r.checked() > 0) {
                log.info("Mutabakat: {} transfer kontrol edildi, {} uyuşmazlık", r.checked(), r.mismatches());
            }
        } catch (Exception e) {
            meters.counter("reconciliation.runs", "result", "error").increment();
            log.warn("Mutabakat turu çalışamadı (account-service erişilemiyor olabilir): {}", e.toString());
        }
    }
}
