package com.example.bank.transfer.service;

import com.example.bank.common.saga.SagaStep;
import com.example.bank.transfer.client.AccountClient.OpResult;
import com.example.bank.transfer.domain.Transfer;

import java.util.Map;
import java.util.Optional;

/**
 * Bir transferin saga sonucu ile account-service'teki işlem kayıtlarının uyumunu denetleyen SAF mantık
 * (DB ve ağ yok, kolay test edilir). Beklenen kayıtlar:
 * <pre>
 *  COMPLETED   : DEBIT başarılı, CREDIT başarılı, REFUND yok
 *  COMPENSATED : DEBIT başarılı, CREDIT reddedilmiş, REFUND başarılı
 *  FAILED      : DEBIT reddedilmiş, CREDIT yok, REFUND yok
 * </pre>
 * Uyuşmazlık = ya transfer-service yanlış sonucu kaydetmiş ya da account-service'te para başka türlü hareket etmiş;
 * ikisi de insan incelemesi gerektirir.
 */
public final class ReconciliationEvaluator {

    public record Mismatch(String kind, String details) {
    }

    private ReconciliationEvaluator() {
    }

    public static String key(Transfer t, SagaStep step) {
        return com.example.bank.common.saga.AccountCommand.opKey(t.getId(), step);
    }

    public static java.util.List<String> keysFor(Transfer t) {
        return java.util.List.of(key(t, SagaStep.DEBIT_SOURCE), key(t, SagaStep.CREDIT_DESTINATION),
                key(t, SagaStep.REFUND_SOURCE));
    }

    public static Optional<Mismatch> evaluate(Transfer t, Map<String, OpResult> ops) {
        OpResult debit = ops.get(key(t, SagaStep.DEBIT_SOURCE));
        OpResult credit = ops.get(key(t, SagaStep.CREDIT_DESTINATION));
        OpResult refund = ops.get(key(t, SagaStep.REFUND_SOURCE));

        return switch (t.getSagaState()) {
            case COMPLETED -> {
                if (debit == null || !debit.success()) {
                    yield mismatch("COMPLETED_WITHOUT_DEBIT", t, "transfer tamamlanmış ama kaynaktan başarılı borçlandırma kaydı yok");
                }
                if (credit == null || !credit.success()) {
                    yield mismatch("COMPLETED_WITHOUT_CREDIT", t, "transfer tamamlanmış ama hedefe başarılı alacaklandırma kaydı yok");
                }
                if (refund != null) {
                    yield mismatch("COMPLETED_WITH_REFUND", t, "transfer tamamlanmış ama iade kaydı da var (çifte hareket)");
                }
                yield Optional.empty();
            }
            case COMPENSATED -> {
                if (debit == null || !debit.success()) {
                    yield mismatch("COMPENSATED_WITHOUT_DEBIT", t, "telafi edilmiş ama başarılı borçlandırma kaydı yok");
                }
                if (credit == null || credit.success()) {
                    yield mismatch("COMPENSATED_WITH_CREDIT", t, "telafi edilmiş ama alacaklandırma reddedilmemiş ("
                            + (credit == null ? "kayıt yok" : "başarılı") + ")");
                }
                if (refund == null || !refund.success()) {
                    yield mismatch("COMPENSATED_WITHOUT_REFUND", t, "telafi edilmiş ama başarılı iade kaydı yok (para clearing'de kalmış olabilir)");
                }
                yield Optional.empty();
            }
            case FAILED -> {
                if (debit == null || debit.success()) {
                    yield mismatch("FAILED_BUT_DEBITED", t, "transfer başarısız görünüyor ama borçlandırma "
                            + (debit == null ? "kaydı yok" : "başarılı (para çıkmış olabilir)"));
                }
                if (credit != null || refund != null) {
                    yield mismatch("FAILED_WITH_LATER_STEPS", t, "transfer başarısız ama sonraki adımlara ait kayıtlar var");
                }
                yield Optional.empty();
            }
            default -> Optional.empty(); // bitmemiş saga mutabakata girmez
        };
    }

    private static Optional<Mismatch> mismatch(String kind, Transfer t, String text) {
        return Optional.of(new Mismatch(kind, "transfer " + t.getId() + " (" + t.getSagaState() + "): " + text));
    }
}
