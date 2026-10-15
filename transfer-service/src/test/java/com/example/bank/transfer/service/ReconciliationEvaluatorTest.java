package com.example.bank.transfer.service;

import com.example.bank.common.domain.Currency;
import com.example.bank.common.domain.TransferType;
import com.example.bank.common.saga.SagaStep;
import com.example.bank.transfer.client.AccountClient.OpResult;
import com.example.bank.transfer.domain.Transfer;
import com.example.bank.transfer.service.ReconciliationEvaluator.Mismatch;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Saf mantık testi: transfer sonucu ile account-service kayıtlarının uyumu. */
class ReconciliationEvaluatorTest {

    private static Transfer transfer() {
        Transfer t = Transfer.start("k", 10L, 20L, new BigDecimal("100"), Currency.TRY, TransferType.EFT, null, "u", 1L);
        ReflectionTestUtils.setField(t, "id", 7L);
        return t;
    }

    private static Transfer completed() {
        Transfer t = transfer();
        t.debited(1L);
        t.credited(2L);
        return t;
    }

    private static Transfer compensated() {
        Transfer t = transfer();
        t.debited(1L);
        t.beginCompensation("ACCOUNT_CLOSED", "kapalı");
        t.compensated();
        return t;
    }

    private static Transfer failed() {
        Transfer t = transfer();
        t.failed("INSUFFICIENT_FUNDS", "yetersiz", 1L);
        return t;
    }

    private static Map<String, OpResult> ops(Transfer t, OpResult debit, OpResult credit, OpResult refund) {
        Map<String, OpResult> m = new HashMap<>();
        put(m, t, SagaStep.DEBIT_SOURCE, debit);
        put(m, t, SagaStep.CREDIT_DESTINATION, credit);
        put(m, t, SagaStep.REFUND_SOURCE, refund);
        return m;
    }

    private static void put(Map<String, OpResult> m, Transfer t, SagaStep step, OpResult r) {
        if (r != null) {
            m.put(ReconciliationEvaluator.key(t, step), r);
        }
    }

    private static OpResult ok() {
        return new OpResult("x", true, null);
    }

    private static OpResult rejected() {
        return new OpResult("x", false, "REJECTED");
    }

    private static String kind(Optional<Mismatch> m) {
        return m.map(Mismatch::kind).orElse("OK");
    }

    @Test
    void completedTransferWithBothSuccessfulStepsIsConsistent() {
        Transfer t = completed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), ok(), null)))).isEqualTo("OK");
    }

    @Test
    void completedTransferMissingCreditIsFlagged() {
        Transfer t = completed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), null, null)))).isEqualTo("COMPLETED_WITHOUT_CREDIT");
    }

    @Test
    void completedTransferWithDebitMissingIsFlagged() {
        Transfer t = completed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, null, ok(), null)))).isEqualTo("COMPLETED_WITHOUT_DEBIT");
    }

    @Test
    void completedTransferThatWasAlsoRefundedIsFlaggedAsDoubleMovement() {
        Transfer t = completed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), ok(), ok())))).isEqualTo("COMPLETED_WITH_REFUND");
    }

    @Test
    void compensatedTransferWithRejectedCreditAndRefundIsConsistent() {
        Transfer t = compensated();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), rejected(), ok())))).isEqualTo("OK");
    }

    @Test
    void compensatedTransferWithoutRefundMeansMoneyMayBeStuckInClearing() {
        Transfer t = compensated();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), rejected(), null)))).isEqualTo("COMPENSATED_WITHOUT_REFUND");
    }

    @Test
    void compensatedTransferWhoseCreditActuallySucceededIsFlagged() {
        Transfer t = compensated();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), ok(), ok())))).isEqualTo("COMPENSATED_WITH_CREDIT");
    }

    @Test
    void failedTransferWithRejectedDebitIsConsistent() {
        Transfer t = failed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, rejected(), null, null)))).isEqualTo("OK");
    }

    @Test
    void failedTransferWhoseDebitSucceededIsTheDangerousCase() {
        Transfer t = failed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, ok(), null, null)))).isEqualTo("FAILED_BUT_DEBITED");
    }

    @Test
    void failedTransferWithLaterStepsIsFlagged() {
        Transfer t = failed();
        assertThat(kind(ReconciliationEvaluator.evaluate(t, ops(t, rejected(), ok(), null)))).isEqualTo("FAILED_WITH_LATER_STEPS");
    }

    @Test
    void inFlightTransfersAreNotReconciled() {
        Transfer t = transfer();
        assertThat(ReconciliationEvaluator.evaluate(t, Map.of())).isEmpty();
    }
}
