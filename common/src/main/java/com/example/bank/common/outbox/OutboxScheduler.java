package com.example.bank.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);
    private static final int MAX_BATCHES_PER_TICK = 20;

    private final OutboxRelay relay;

    public OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    /** fixedDelay: önceki tur bitmeden yenisi başlamaz (üst üste binme olmaz). */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void poll() {
        try {
            // Birikme varsa aynı tur içinde birkaç batch üst üste boşalt.
            for (int i = 0; i < MAX_BATCHES_PER_TICK; i++) {
                if (relay.publishBatch() == 0) {
                    break;
                }
            }
        } catch (Exception e) {
            // DB erişilemezse vb. Zamanlayıcı ölmesin, sonraki turda tekrar denenir.
            log.error("Outbox turu başarısız oldu", e);
        }
    }
}
