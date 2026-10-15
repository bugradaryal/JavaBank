package com.example.bank.transfer.domain;

import com.example.bank.common.domain.TransferStatus;

/**
 * Saga durum makinesi (orkestratör tarafında):
 * <pre>
 * STARTED --debit ok--> DEBITED --credit ok--> COMPLETED
 *    |                     |
 *    | debit red           | credit red
 *    v                     v
 *  FAILED            COMPENSATING --refund ok--> COMPENSATED
 * </pre>
 * FAILED: hiç para hareket etmedi. COMPENSATED: para çekildi, hedefe geçemedi, kaynağa iade edildi.
 */
public enum SagaState {
    STARTED(TransferStatus.PENDING, false),
    DEBITED(TransferStatus.PENDING, false),
    COMPENSATING(TransferStatus.PENDING, false),
    COMPLETED(TransferStatus.COMPLETED, true),
    FAILED(TransferStatus.FAILED, true),
    COMPENSATED(TransferStatus.FAILED, true);

    private final TransferStatus status;
    private final boolean terminal;

    SagaState(TransferStatus status, boolean terminal) {
        this.status = status;
        this.terminal = terminal;
    }

    public TransferStatus status() {
        return status;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
