package com.example.bank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Uçtan uca HTTP akışı: kayıt, giriş, hesap açma, para yatırma, transfer, yetki kontrolleri. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ApiFlowIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void customerJourneyAndAuthorizationRules() {
        String token1 = register(UUID.randomUUID() + "@flow.test");
        String token2 = register(UUID.randomUUID() + "@flow.test");
        String tellerToken = login("teller", "Teller123!");

        long acct1 = openAccount(token1);
        long acct2 = openAccount(token2);

        // Müşteri para yatıramaz, personel yatırır.
        assertThat(post("/api/accounts/" + acct1 + "/deposit", token1, key(),
                Map.of("amount", 500, "description", "deneme")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> deposit = post("/api/accounts/" + acct1 + "/deposit", tellerToken, key(),
                Map.of("amount", 500, "description", "maaş"));
        assertThat(deposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Transfer: ilk istek 201, aynı key ile tekrar 200 + Idempotent-Replayed.
        String transferKey = key();
        Map<String, Object> body = Map.of("fromAccountId", acct1, "toAccountId", acct2,
                "amount", 200, "type", "EFT");
        ResponseEntity<Map> first = post("/api/transfers", token1, transferKey, body);
        ResponseEntity<Map> second = post("/api/transfers", token1, transferKey, body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));

        // Bakiye sadece bir kez düştü.
        assertThat(balanceOf(acct1, token1)).isEqualByComparingTo("300");
        assertThat(balanceOf(acct2, token2)).isEqualByComparingTo("200");

        // Başkasının hesabına erişim ve başkasının hesabından transfer yasak.
        assertThat(get("/api/accounts/" + acct1, token2).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/api/transfers", token2, key(),
                Map.of("fromAccountId", acct1, "toAccountId", acct2, "amount", 10, "type", "EFT"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Idempotency-Key olmadan transfer olmaz.
        ResponseEntity<Map> noKey = post("/api/transfers", token1, null,
                Map.of("fromAccountId", acct1, "toAccountId", acct2, "amount", 10, "type", "EFT"));
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_MISSING");

        // Yetersiz bakiye: 422 ve FAILED kaydı.
        ResponseEntity<Map> tooMuch = post("/api/transfers", token1, key(),
                Map.of("fromAccountId", acct1, "toAccountId", acct2, "amount", 100000, "type", "EFT"));
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.getBody().get("status")).isEqualTo("FAILED");

        // Token olmadan 401, müşteri audit log göremez.
        assertThat(rest.getForEntity("/api/accounts", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/audit-logs", token1).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertGlobalInvariant();
    }

    private String register(String email) {
        ResponseEntity<Map> r = rest.postForEntity("/api/auth/register",
                Map.of("fullName", "Test Kullanıcı", "email", email, "password", "password123"), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("token");
    }

    private String login(String username, String password) {
        ResponseEntity<Map> r = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("token");
    }

    private long openAccount(String token) {
        ResponseEntity<Map> r = post("/api/accounts", token, null, Map.of("currency", "TRY"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) r.getBody().get("id")).longValue();
    }

    private BigDecimal balanceOf(long accountId, String token) {
        ResponseEntity<Map> r = get("/api/accounts/" + accountId, token);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new BigDecimal(String.valueOf(r.getBody().get("balance")));
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token, null)), Map.class);
    }

    private ResponseEntity<Map> post(String path, String token, String idempotencyKey, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token, idempotencyKey)),
                Map.class);
    }

    private HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        return h;
    }

    private static String key() {
        return "flow-" + UUID.randomUUID();
    }
}
