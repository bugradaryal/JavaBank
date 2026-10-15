package com.example.bank.transfer.service;

import com.example.bank.transfer.domain.SagaState;
import com.example.bank.transfer.repository.TransferRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Saga durumlarının anlık dağılımı: saga.in.flight{state}. STARTED/DEBITED/COMPENSATING sayısı uzun süre
 * sıfırdan büyükse saga'lar takılıyor demektir (alarm: SagaStuck). Hata olursa NaN döner, scrape bozulmaz.
 */
@Component
public class SagaMetrics implements MeterBinder {

    private final TransferRepository transfers;

    public SagaMetrics(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (SagaState state : SagaState.values()) {
            Gauge.builder("saga.in.flight", this, m -> m.count(state))
                    .tag("state", state.name())
                    .description("Duruma göre transfer sayısı (terminal durumlarda toplam)").register(registry);
        }
    }

    private double count(SagaState state) {
        try {
            return transfers.countBySagaState(state);
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}
