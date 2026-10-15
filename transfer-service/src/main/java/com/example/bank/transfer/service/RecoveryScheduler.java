package com.example.bank.transfer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Bir adımı step-timeout'tan uzun süredir cevapsız kalan saga'ları kurtarır (komut/cevap kaybı, servis çökmesi). */
@Component
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final TransferSagaService saga;
    private final Duration stepTimeout;
    private final int batch;

    public RecoveryScheduler(TransferSagaService saga,
                             @Value("${app.saga.step-timeout-ms:15000}") long stepTimeoutMs,
                             @Value("${app.saga.recovery-batch:50}") int batch) {
        this.saga = saga;
        this.stepTimeout = Duration.ofMillis(stepTimeoutMs);
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${app.saga.recovery-interval-ms:5000}")
    public void run() {
        try {
            int n = saga.recoverStuck(Instant.now().minus(stepTimeout), batch);
            if (n > 0) {
                log.info("{} takılmış saga yeniden tetiklendi", n);
            }
        } catch (Exception e) {
            log.error("Saga recovery turu başarısız oldu", e);
        }
    }
}
