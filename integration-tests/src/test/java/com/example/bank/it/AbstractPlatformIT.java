package com.example.bank.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Sistem testlerinin ortak tabanı: üç servisi başlatır, müşteri/hesap/transfer yardımcılarını ve
 * değişmez kontrollerini (Σ bakiye = 0, clearing = 0) sağlar. Docker gerekir.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractPlatformIT {

    protected static final Duration LONG = Duration.ofSeconds(90);

    protected Platform platform;
    protected String admin;
    protected String teller;

    record Customer(long customerId, String token) {
    }

    record Acct(long id, Customer owner) {
    }

    /** Saga adımı bu kadar süre cevapsız kalırsa recovery job komutu yeniden gönderir. */
    protected long stepTimeoutMs() {
        return 2_000;
    }

    @BeforeAll
    void start() throws Exception {
        platform = new Platform(stepTimeoutMs());
        platform.start();
        admin = login("admin", "Admin123!");
        teller = login("teller", "Teller123!");
    }

    @AfterAll
    void stop() {
        if (platform != null) {
            platform.close();
        }
    }

    protected String login(String username, String password) {
        Api.Resp r = Api.post(platform.accountUrl() + "/api/auth/login", null,
                Map.of("username", username, "password", password), Map.of());
        assertThat(r.status()).isEqualTo(200);
        return r.body().get("token").asText();
    }

    protected Customer register() {
        String email = UUID.randomUUID() + "@test.local";
        Api.Resp r = Api.post(platform.accountUrl() + "/api/auth/register", null,
                Map.of("fullName", "Test Kullanici", "email", email, "password", "Passw0rd!x"), Map.of());
        assertThat(r.status()).isEqualTo(201);
        return new Customer(r.body().get("customerId").asLong(), r.body().get("token").asText());
    }

    /** Yeni müşteri + TRY hesabı; initial > 0 ise gişeden yatırır. */
    protected Acct account(String initial) {
        Customer c = register();
        Api.Resp r = Api.post(platform.accountUrl() + "/api/accounts", teller,
                Map.of("currency", "TRY", "customerId", c.customerId), Map.of());
        assertThat(r.status()).isEqualTo(201);
        Acct acct = new Acct(r.body().get("id").asLong(), c);
        if (new BigDecimal(initial).signum() > 0) {
            Api.Resp d = Api.post(platform.transferUrl() + "/api/transfers/deposit?wait=10000", teller,
                    Map.of("accountId", acct.id, "amount", initial, "currency", "TRY"),
                    Map.of("Idempotency-Key", "seed-" + UUID.randomUUID()));
            assertThat(d.status()).isEqualTo(201);
        }
        return acct;
    }

    protected void close(Acct a) {
        Api.Resp r = Api.post(platform.accountUrl() + "/api/accounts/" + a.id + "/close", teller, Map.of(), Map.of());
        assertThat(r.status()).isEqualTo(200);
    }

    protected static Map<String, Object> transferBody(Acct from, long toAccountId, String amount) {
        return Map.of("fromAccountId", from.id, "toAccountId", toAccountId, "amount", amount,
                "currency", "TRY", "type", "EFT");
    }

    protected Api.Resp post(String token, String path, Object body, String key) {
        return Api.post(platform.transferUrl() + path, token, body, Map.of("Idempotency-Key", key));
    }

    /** Transferi başlatır ve sonuçlanmasını bekler (201 COMPLETED). */
    protected JsonNode transfer(Acct from, long to, String amount, String key) {
        return transferExpecting(201, from, to, amount, key);
    }

    protected JsonNode transferExpecting(int status, Acct from, long to, String amount, String key) {
        Api.Resp r = post(from.owner.token, "/api/transfers?wait=10000", transferBody(from, to, amount), key);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(status);
        return r.body();
    }

    /** Beklemeden başlatır: 202 PENDING. */
    protected JsonNode startTransfer(Acct from, long to, String amount, String key) {
        Api.Resp r = post(from.owner.token, "/api/transfers", transferBody(from, to, amount), key);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(202);
        return r.body();
    }

    protected String state(long transferId) {
        return Api.get(platform.transferUrl() + "/api/transfers/" + transferId, admin).body()
                .get("sagaState").asText();
    }

    protected long attempts(long transferId) {
        try {
            return platform.count("transferdb", "select attempts from transfer where id = ?", transferId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected BigDecimal balance(Acct a) {
        Api.Resp r = Api.get(platform.accountUrl() + "/api/accounts/" + a.id, teller);
        return new BigDecimal(r.body().get("balance").asText());
    }

    protected JsonNode summary() {
        return Api.get(platform.accountUrl() + "/api/admin/ledger-summary", admin).body();
    }

    protected BigDecimal total() {
        return new BigDecimal(summary().get("totalBalance").asText());
    }

    protected BigDecimal clearing() {
        return new BigDecimal(summary().get("clearingBalance").asText());
    }

    /** Σ bakiye = 0 (para kaybolmadı/yoktan çıkmadı) ve bitmiş saga'larda clearing = 0 (yolda para yok). */
    protected void assertMoneySafe() {
        assertThat(total()).as("Σ tüm bakiyeler").isEqualByComparingTo("0");
        await().atMost(LONG).untilAsserted(
                () -> assertThat(clearing()).as("clearing (yoldaki para)").isEqualByComparingTo("0"));
    }
}
