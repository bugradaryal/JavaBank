package com.example.bank.account.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Muhasebe kontrolünü periyodik çalıştırır; ihlal sayısını metrik olarak yayınlar (alarm: LedgerViolations). */
@Component
public class LedgerReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(LedgerReconciliationJob.class);

    private final LedgerReconciliationService service;
    private final MeterRegistry meters;
    private final AtomicInteger violations = new AtomicInteger(0);

    public LedgerReconciliationJob(LedgerReconciliationService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
        Gauge.builder("reconciliation.account.violations", violations, AtomicInteger::get)
                .description("Son muhasebe kontrolünde bulunan ihlal sayısı (0 olmalı)").register(meters);
    }

    @Scheduled(initialDelayString = "${app.reconciliation.initial-delay-ms:60000}",
            fixedDelayString = "${app.reconciliation.interval-ms:300000}")
    public void run() {
        try {
            LedgerReconciliationService.Report report = service.run();
            violations.set(report.violations().size());
            meters.counter("reconciliation.runs", "result", report.ok() ? "ok" : "violations").increment();
            if (report.ok()) {
                log.info("Muhasebe kontrolü temiz: {} hesap, {} ledger kaydı, clearing {}",
                        report.accountsChecked(), report.ledgerEntriesChecked(), report.clearingBalance());
            } else {
                log.error("MUHASEBE İHLALİ: {} bulgu, ilki: {}", report.violations().size(),
                        report.violations().get(0));
            }
        } catch (Exception e) {
            meters.counter("reconciliation.runs", "result", "error").increment();
            log.error("Muhasebe kontrolü çalışamadı", e);
        }
    }
}
