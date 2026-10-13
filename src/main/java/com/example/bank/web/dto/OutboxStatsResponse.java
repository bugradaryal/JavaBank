package com.example.bank.web.dto;

import java.time.Instant;

/** pending: henüz Kafka'ya gönderilmemiş event sayısı. Sağlıklı sistemde ~0'dır; sürekli artıyorsa Kafka'da sorun var. */
public record OutboxStatsResponse(
        long pending,
        long published,
        Instant oldestPendingAt) {
}
