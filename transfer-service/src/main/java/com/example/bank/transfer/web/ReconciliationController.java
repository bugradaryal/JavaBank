package com.example.bank.transfer.web;

import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import com.example.bank.transfer.domain.ReconciliationFinding;
import com.example.bank.transfer.repository.ReconciliationFindingRepository;
import com.example.bank.transfer.repository.TransferRepository;
import com.example.bank.transfer.service.ReconciliationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class ReconciliationController {

    public record Summary(long unreconciled, long openFindings, List<ReconciliationFinding> findings) {
    }

    private final ReconciliationService service;
    private final TransferRepository transfers;
    private final ReconciliationFindingRepository findings;

    public ReconciliationController(ReconciliationService service, TransferRepository transfers,
                                    ReconciliationFindingRepository findings) {
        this.service = service;
        this.transfers = transfers;
        this.findings = findings;
    }

    @GetMapping
    public Summary summary() {
        return new Summary(transfers.countUnreconciled(), findings.countByResolvedAtIsNull(),
                findings.findTop100ByResolvedAtIsNullOrderByIdDesc());
    }

    /** Mutabakatı hemen çalıştırır (zamanlayıcıyı beklemeden). */
    @PostMapping("/run")
    public ReconciliationService.Result run() {
        return service.runOnce();
    }

    /** İncelenip kapatılan bulgu. */
    @PostMapping("/findings/{id}/resolve")
    @Transactional
    public void resolve(@PathVariable Long id) {
        findings.findById(id)
                .orElseThrow(() -> new BankException(ErrorCode.CONFLICT, "Bulgu bulunamadı: " + id))
                .resolve();
    }
}
