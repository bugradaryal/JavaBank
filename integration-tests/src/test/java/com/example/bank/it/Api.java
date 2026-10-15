package com.example.bank.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Servislerin HTTP API'sini çağıran küçük test istemcisi. */
final class Api {

    record Resp(int status, JsonNode body) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Api() {
    }

    static Resp post(String url, String token, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            headers.forEach(b::header);
            return send(b.build());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Resp get(String url, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return send(b.build());
    }

    private static Resp send(HttpRequest request) {
        try {
            HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body() == null || r.body().isBlank() ? MAPPER.nullNode() : MAPPER.readTree(r.body());
            return new Resp(r.statusCode(), json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
