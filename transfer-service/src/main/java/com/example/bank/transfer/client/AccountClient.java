package com.example.bank.transfer.client;

import com.example.bank.common.domain.Role;
import com.example.bank.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * account-service'in mutabakat ucunu çağırır. Servisler arası kimlik doğrulama: ortak JWT anahtarıyla kısa ömürlü
 * bir ADMIN token üretilir. Basit ve gösterim için yeterli; gerçekte mTLS veya servis hesabı + asimetrik anahtar.
 */
@Component
public class AccountClient {

    public record OpResult(String opKey, boolean success, String reasonCode) {
    }

    private record Request(List<String> opKeys) {
    }

    private final RestClient http;
    private final JwtService jwt;

    public AccountClient(@Value("${app.account-service.url:http://localhost:8081}") String baseUrl, JwtService jwt) {
        this.jwt = jwt;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Verilen işlem anahtarlarının account-service'teki sonucu. Kaydı olmayan anahtar sonuçta yer almaz. */
    public Map<String, OpResult> lookup(List<String> opKeys) {
        List<OpResult> results = http.post()
                .uri("/api/admin/reconciliation/operations")
                .header("Authorization", "Bearer " + jwt.generate(0L, "svc-reconciliation", Role.ADMIN, null))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Request(opKeys))
                .retrieve()
                .body(new ParameterizedTypeReference<List<OpResult>>() {
                });
        Map<String, OpResult> byKey = new HashMap<>();
        if (results != null) {
            results.forEach(r -> byKey.put(r.opKey(), r));
        }
        return byKey;
    }
}
