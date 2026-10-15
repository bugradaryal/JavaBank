package com.example.bank.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mutabakat (reconciliation) testleri. İki yön:
 * <ul>
 *   <li>sağlıklı sistemde (başarılı, reddedilen, telafi edilen transferler) hiçbir uyuşmazlık bulunmaz;</li>
 *   <li>veri kasten bozulduğunda mutabakat bunu YAKALAR (bozulmayı gerçekten gördüğünü kanıtlar).</li>
 * </ul>
 * Bozma testleri işi bitince veriyi eski haline getirir.
 */
class ReconciliationIT extends AbstractPlatformIT {

    private JsonNode runReconciliation() {
        Api.Resp r = Api.post(platform.transferUrl() + "/api/admin/reconciliation/run", admin, Map.of(), Map.of());
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private Set<Long> openFindingTransferIds() {
        JsonNode summary = Api.get(platform.transferUrl() + "/api/admin/reconciliation", admin).body();
        Set<Long> ids = new HashSet<>();
        summary.get("findings").forEach(f -> ids.add(f.get("transferId").asLong()));
        return ids;
    }

    private JsonNode findingOf(long transferId) {
        JsonNode summary = Api.get(platform.transferUrl() + "/api/admin/reconciliation", admin).body();
        for (JsonNode f : summary.get("findings")) {
            if (f.get("transferId").asLong() == transferId) {
                return f;
            }
        }
        return null;
    }

    private JsonNode accountReport() {
        Api.Resp r = Api.get(platform.accountUrl() + "/api/admin/reconciliation", admin);
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    private boolean hasViolation(JsonNode report, String check) {
        for (JsonNode v : report.get("violations")) {
            if (check.equals(v.get("check").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void healthySystemHasNoFindingsForCompletedFailedAndCompensatedTransfers() {
        Acct a = account("1000.0000");
        Acct b = account("0.0000");
        Acct closed = account("0.0000");
        close(closed);

        long completed = transfer(a, b.id, "100.0000", "rc-ok-" + UUID.randomUUID()).get("id").asLong();
        long failed = transferExpecting(422, b, a.id, "999.0000", "rc-nsf-" + UUID.randomUUID()).get("id").asLong();
        JsonNode comp = transferExpecting(422, a, closed.id, "50.0000", "rc-comp-" + UUID.randomUUID());
        long compensated = comp.get("id").asLong();
        assertThat(comp.get("sagaState").asText()).isEqualTo("COMPENSATED");

        JsonNode result = runReconciliation();

        assertThat(result.get("checked").asInt()).isGreaterThanOrEqualTo(3);
        Set<Long> flagged = openFindingTransferIds();
        assertThat(flagged).doesNotContain(completed, failed, compensated);
        assertThat(accountReport().get("violations")).isEmpty();
    }

    @Test
    void detectsTransferThatClaimsCompletionButNeverMovedMoney() throws Exception {
        Acct a = account("10.0000");
        Acct b = account("0.0000");
        long id = transferExpecting(422, a, b.id, "999.0000", "rc-tamper-" + UUID.randomUUID()).get("id").asLong();
        runReconciliation(); // temiz kayıt olarak işaretlenir

        // Kasıtlı bozma: başarısız transferi "tamamlandı" göster (transfer-service'in yanlış sonuç kaydettiği durum).
        platform.execute("transferdb",
                "update transfer set saga_state = 'COMPLETED', reconciled_at = null where id = ?", id);
        try {
            runReconciliation();

            JsonNode finding = findingOf(id);
            assertThat(finding).as("uyuşmazlık bulunmalı").isNotNull();
            assertThat(finding.get("kind").asText()).isEqualTo("COMPLETED_WITHOUT_DEBIT");
            Api.post(platform.transferUrl() + "/api/admin/reconciliation/findings/"
                    + finding.get("id").asLong() + "/resolve", admin, Map.of(), Map.of());
        } finally {
            platform.execute("transferdb", "update transfer set saga_state = 'FAILED' where id = ?", id);
        }
        assertThat(findingOf(id)).isNull();
    }

    @Test
    void detectsLedgerCorruptionInsideAccountService() throws Exception {
        Acct a = account("100.0000");

        // 1) Bakiye ledger ile uyuşmuyor ve toplam 0 değil.
        platform.execute("accountdb", "update account set balance = balance + 5 where id = ?", a.id());
        try {
            JsonNode report = accountReport();
            assertThat(hasViolation(report, "BALANCE_LEDGER_MISMATCH")).isTrue();
            assertThat(hasViolation(report, "TOTAL_NOT_ZERO")).isTrue();
        } finally {
            platform.execute("accountdb", "update account set balance = balance - 5 where id = ?", a.id());
        }
        assertThat(accountReport().get("violations")).isEmpty();

        // 2) İşlem kaydı olmayan, tek taraflı (dengesiz) ledger kaydı.
        platform.execute("accountdb", "insert into ledger_entry (account_id, reference, entry_type, amount, balance_after) "
                + "values (?, 'fake:1', 'CREDIT', 1, 1)", a.id());
        try {
            JsonNode report = accountReport();
            assertThat(hasViolation(report, "ENTRY_WITHOUT_OPERATION")).isTrue();
            assertThat(hasViolation(report, "OPERATION_NOT_BALANCED")).isTrue();
        } finally {
            platform.execute("accountdb", "delete from ledger_entry where reference = 'fake:1'");
        }
        assertThat(accountReport().get("violations")).isEmpty();
    }
}
