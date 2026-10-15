package com.example.bank.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Outbox birikmesi: pending sayısı ve en eski bekleyen mesajın yaşı. Kafka erişilemezse veya publisher
 * takılırsa ilk burada görünür (alarm: OutboxBacklog).
 * Gauge'lar scrape sırasında DB'ye sorgu atar (kısmi index sayesinde ucuz); hata olursa NaN döner, scrape bozulmaz.
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxEventRepository events;

    public OutboxMetrics(OutboxEventRepository events) {
        this.events = events;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("outbox.pending", this, m -> m.safe(() -> (double) m.events.countByPublishedAtIsNull()))
                .description("Henüz Kafka'ya gönderilmemiş outbox mesajı sayısı").register(registry);
        Gauge.builder("outbox.oldest.pending.age.seconds", this, m -> m.safe(m::oldestAgeSeconds))
                .description("En eski bekleyen outbox mesajının yaşı (bekleyen yoksa 0)")
                .baseUnit("seconds").register(registry);
    }

    private double oldestAgeSeconds() {
        Instant oldest = events.oldestPendingCreatedAt();
        return oldest == null ? 0 : Duration.between(oldest, Instant.now()).toMillis() / 1000.0;
    }

    private double safe(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (Exception e) {
            log.debug("Outbox metriği okunamadı: {}", e.toString());
            return Double.NaN;
        }
    }
}
