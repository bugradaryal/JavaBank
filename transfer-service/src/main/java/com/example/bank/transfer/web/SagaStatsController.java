package com.example.bank.transfer.web;

import com.example.bank.transfer.domain.SagaState;
import com.example.bank.transfer.repository.TransferRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.Map;

/** Saga durumlarının dağılımı. Bitmemiş (STARTED/DEBITED/COMPENSATING) sayısı uzun süre sıfırdan büyükse sorun var demektir. */
@RestController
@RequestMapping("/api/admin/saga-stats")
public class SagaStatsController {

    private final TransferRepository transfers;

    public SagaStatsController(TransferRepository transfers) {
        this.transfers = transfers;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<SagaState, Long> stats() {
        Map<SagaState, Long> result = new EnumMap<>(SagaState.class);
        for (SagaState s : SagaState.values()) {
            result.put(s, transfers.countBySagaState(s));
        }
        return result;
    }
}
