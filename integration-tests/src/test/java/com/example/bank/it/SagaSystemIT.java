package com.example.bank.it;

import com.example.bank.account.messaging.AccountCommandListener;
import com.example.bank.transfer.messaging.AccountReplyListener;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Sistem testi: üç servis + gerçek PostgreSQL + gerçek Kafka. Docker gerekir.
 *
 * <p>Her test sonunda iki değişmezi doğrular:
 * <ol>
 *   <li>Σ tüm bakiyeler (müşteri + kasa + clearing) = 0 — para yoktan var olmaz, kaybolmaz;</li>
 *   <li>tüm saga'lar bittiğinde clearing hesapları 0 — yolda para kalmaz.</li>
 * </ol>
 * Servis çökmesi, context'i kapatıp yeniden başlatarak simüle edilir (bellekteki hiçbir şey kalmaz).
 */
class SagaSystemIT extends AbstractPlatformIT {

    // ------------------------------------------------------------------ senaryolar

    @Test
    void happyPathMovesMoneyAndNotifiesBothCustomers() throws Exception {
        Acct a = account("1000.0000");
        Acct b = account("0.0000");

        JsonNode t = transfer(a, b.id, "300.0000", "ok-" + UUID.randomUUID());
        assertThat(t.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(t.get("sagaState").asText()).isEqualTo("COMPLETED");

        assertThat(balance(a)).isEqualByComparingTo("700");
        assertThat(balance(b)).isEqualByComparingTo("300");
        long transferId = t.get("id").asLong();
        await().atMost(LONG).untilAsserted(() -> assertThat(platform.count("notificationdb",
                "select count(*) from notification where transfer_id = ?", transferId)).isEqualTo(2));
        assertMoneySafe();
    }

    @Test
    void insufficientFundsFailsCleanlyWithoutMovingMoney() {
        Acct a = account("100.0000");
        Acct b = account("0.0000");

        JsonNode t = transferExpecting(422, a, b.id, "500.0000", "nsf-" + UUID.randomUUID());

        assertThat(t.get("status").asText()).isEqualTo("FAILED");
        assertThat(t.get("sagaState").asText()).isEqualTo("FAILED"); // para hiç çıkmadığı için telafi gerekmedi
        assertThat(t.get("failureCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(a)).isEqualByComparingTo("100");
        assertThat(balance(b)).isEqualByComparingTo("0");
        assertMoneySafe();
    }

    @Test
    void failedCreditStepIsCompensatedAndMoneyReturnsToSender() {
        Acct a = account("500.0000");
        Acct b = account("0.0000");
        close(b); // hedef hesap kapalı: borçlandırma adımı geçer, alacaklandırma reddedilir -> telafi

        JsonNode t = transferExpecting(422, a, b.id, "200.0000", "comp-" + UUID.randomUUID());

        assertThat(t.get("sagaState").asText()).isEqualTo("COMPENSATED");
        assertThat(t.get("failureCode").asText()).isEqualTo("ACCOUNT_CLOSED");
        assertThat(balance(a)).isEqualByComparingTo("500"); // para geri geldi
        assertThat(balance(b)).isEqualByComparingTo("0");
        assertMoneySafe();
    }

    @Test
    void accountServiceCrashingMidSagaLosesNoMoneyAndSagaCompletesAfterRestart() {
        Acct a = account("1000.0000");
        Acct b = account("0.0000");

        // Alacaklandırma adımını askıya al: debit işlendi, credit komutu Kafka'da bekliyor.
        platform.pause(platform.accountContext(), AccountCommandListener.CREDIT_LISTENER);
        JsonNode started = startTransfer(a, b.id, "400.0000", "crash-acc-" + UUID.randomUUID());
        long id = started.get("id").asLong();
        await().atMost(LONG).untilAsserted(() -> assertThat(state(id)).isEqualTo("DEBITED"));

        // Ara durumda para "havada" değil clearing hesabında: toplam hâlâ 0, gönderenden düşmüş, alıcıya geçmemiş.
        assertThat(balance(a)).isEqualByComparingTo("600");
        assertThat(balance(b)).isEqualByComparingTo("0");
        assertThat(clearing()).isEqualByComparingTo("400");
        assertThat(total()).isEqualByComparingTo("0");

        // account-service çöker ve yeniden başlar; commit edilmemiş credit komutu Kafka'dan tekrar okunur.
        platform.crashAccount();
        platform.startAccount();

        await().atMost(LONG).untilAsserted(() -> assertThat(state(id)).isEqualTo("COMPLETED"));
        assertThat(balance(a)).isEqualByComparingTo("600");
        assertThat(balance(b)).isEqualByComparingTo("400");
        assertMoneySafe();
    }

    @Test
    void transferServiceCrashingBetweenStepsResumesFromDurableState() {
        Acct a = account("1000.0000");
        Acct b = account("0.0000");

        // Orkestratör cevapları işlemesin: account-service debit'i yapar ama transfer-service haberdar olmaz.
        platform.pause(platform.transferContext(), AccountReplyListener.LISTENER_ID);
        JsonNode started = startTransfer(a, b.id, "250.0000", "crash-trf-" + UUID.randomUUID());
        long id = started.get("id").asLong();
        await().atMost(LONG).untilAsserted(() -> assertThat(balance(a)).isEqualByComparingTo("750"));
        assertThat(state(id)).isEqualTo("STARTED"); // orkestratör hâlâ eski durumda

        platform.crashTransfer();
        platform.startTransfer();

        // Yeni örnek durumu DB'den okur, cevabı işler ve saga'yı bitirir.
        await().atMost(LONG).untilAsserted(() -> assertThat(state(id)).isEqualTo("COMPLETED"));
        assertThat(balance(a)).isEqualByComparingTo("750");
        assertThat(balance(b)).isEqualByComparingTo("250");
        assertMoneySafe();
    }

    @Test
    void stuckStepIsResentAndDuplicateCommandsAreAppliedOnlyOnce() {
        Acct a = account("1000.0000");
        Acct b = account("0.0000");

        // Debit komutlarını bir süre işleme: recovery job (2 sn timeout) aynı komutu tekrar tekrar gönderir.
        platform.pause(platform.accountContext(), AccountCommandListener.DEBIT_LISTENER);
        JsonNode started = startTransfer(a, b.id, "100.0000", "dup-" + UUID.randomUUID());
        long id = started.get("id").asLong();
        await().atMost(LONG).untilAsserted(() -> assertThat(attempts(id)).isGreaterThanOrEqualTo(2));

        platform.resume(platform.accountContext(), AccountCommandListener.DEBIT_LISTENER);

        await().atMost(LONG).untilAsserted(() -> assertThat(state(id)).isEqualTo("COMPLETED"));
        // Kafka'da birden çok kopya vardı ama para bir kez hareket etti.
        assertThat(balance(a)).isEqualByComparingTo("900");
        assertThat(balance(b)).isEqualByComparingTo("100");
        assertMoneySafe();
    }

    @Test
    void sameIdempotencyKeyDoesNotStartASecondSaga() {
        Acct a = account("1000.0000");
        Acct b = account("0.0000");
        String key = "idem-" + UUID.randomUUID();

        JsonNode first = transfer(a, b.id, "50.0000", key);
        Api.Resp second = post(a.owner.token, "/api/transfers?wait=10000", transferBody(a, b.id, "50.0000"), key);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(balance(a)).isEqualByComparingTo("950");
        assertMoneySafe();
    }

    @Test
    void customerCannotSpendFromSomeoneElsesAccount() {
        Acct victim = account("1000.0000");
        Acct attacker = account("0.0000");

        // Saldırgan kendi token'ıyla kurbanın hesabını kaynak gösteriyor.
        Api.Resp r = post(attacker.owner.token, "/api/transfers?wait=10000",
                transferBody(victim, attacker.id, "900.0000"), "steal-" + UUID.randomUUID());

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body().get("failureCode").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(balance(victim)).isEqualByComparingTo("1000");
        assertThat(balance(attacker)).isEqualByComparingTo("0");
        assertMoneySafe();
    }
}
