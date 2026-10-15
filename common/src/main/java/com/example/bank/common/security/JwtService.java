package com.example.bank.common.security;

import com.example.bank.common.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Token'ı account-service üretir; üç servis de aynı gizli anahtarla doğrular (HMAC).
 * Gerçek ortamda anahtar bir secret manager'dan gelir ya da asimetrik anahtar (RS256) kullanılır;
 * böylece diğer servisler sadece public key'i bilir ve token üretemez.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes}") long expirationMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret en az 32 byte olmalı");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = expirationMinutes * 60;
    }

    public String generate(Long userId, String username, Role role, Long customerId) {
        Instant now = Instant.now();
        JwtBuilder builder = Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("role", role.name());
        if (customerId != null) {
            builder.claim("cid", customerId);
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthUser> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            Number uid = claims.get("uid", Number.class);
            Number cid = claims.get("cid", Number.class);
            Role role = Role.valueOf(claims.get("role", String.class));
            return Optional.of(new AuthUser(
                    uid == null ? null : uid.longValue(),
                    claims.getSubject(),
                    role,
                    cid == null ? null : cid.longValue()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
