package com.example.bank.account.web;

import com.example.bank.account.domain.OperationLog;
import com.example.bank.account.repository.OperationLogRepository;
import com.example.bank.account.service.LedgerReconciliationService;
import com.example.bank.common.error.BankException;
import com.example.bank.common.error.ErrorCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Mutabakat uçları. İkincisi, transfer-service'in kendi kayıtlarını bu servisin işlem kayıtlarıyla karşılaştırması içindir. */
@RestController
@RequestMapping("/api/admin/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class ReconciliationController {

    static final int MAX_LOOKUP = 500;

    public record OperationsRequest(List<String> opKeys) {
    }

    public record OperationResult(String opKey, boolean success, String reasonCode) {
    }

    private final LedgerReconciliationService reconciliation;
    private final OperationLogRepository operations;

    public ReconciliationController(LedgerReconciliationService reconciliation, OperationLogRepository operations) {
        this.reconciliation = reconciliation;
        this.operations = operations;
    }

    /** Şimdi çalıştırır ve raporu döner. */
    @GetMapping
    public LedgerReconciliationService.Report report() {
        return reconciliation.run();
    }

    /** Verilen işlem anahtarlarından (transferId:ADIM) bu serviste kayıtlı olanları döner; olmayanlar listede yer almaz. */
    @PostMapping("/operations")
    @Transactional(readOnly = true)
    public List<OperationResult> operations(@RequestBody OperationsRequest request) {
        if (request.opKeys() == null || request.opKeys().size() > MAX_LOOKUP) {
            throw new BankException(ErrorCode.VALIDATION_ERROR, "opKeys en fazla " + MAX_LOOKUP + " eleman olabilir");
        }
        return operations.findAllById(request.opKeys()).stream()
                .map((OperationLog o) -> new OperationResult(o.getOpKey(), o.isSuccess(), o.getReasonCode()))
                .toList();
    }
}
