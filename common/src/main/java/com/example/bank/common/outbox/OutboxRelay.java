package com.example.bank.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Outbox'taki yayınlanmamış mesajları Kafka'ya gönderir (her servisin kendi outbox tablosu için).
 *
 * <p>Garanti: at-least-once. Gönderim başarılı olup commit başarısız olursa (veya ack kaybolursa) aynı mesaj
 * ikinci kez gönderilir; bu yüzden alıcılar idempotent olmak zorundadır.
 *
 * <p>Sıra: batch seq sırasıyla işlenir ve ilk hatada durur; Kafka kapalıyken sıradaki mesajlar atlanıp sırası bozulmaz.
 *
 * <p>Bedel: Kafka ack'ini beklerken DB transaction'ı (ve satır kilitleri) açık kalır. Batch küçük ve timeout kısa
 * tutulur; çok yüksek hacimde bu bölüm Debezium gibi bir CDC çözümüyle değiştirilebilir.
 *
 * <p>Ayrı bir bean: @Transactional proxy ile çalışır, scheduler'dan bu sınıfa dışarıdan çağrı yapılır.
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository events;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final long sendTimeoutMs;
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxRelay(OutboxEventRepository events,
                       KafkaTemplate<String, String> kafka,
                       MeterRegistry meters,
                       Tracer tracer,
                       Propagator propagator,
                       @Value("${app.outbox.batch-size:50}") int batchSize,
                       @Value("${app.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.events = events;
        this.kafka = kafka;
        this.tracer = tracer;
        this.propagator = propagator;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
        this.publishedCounter = Counter.builder("outbox.published")
                .description("Kafka'ya başarıyla gönderilen outbox mesajları").register(meters);
        this.failedCounter = Counter.builder("outbox.publish.failures")
                .description("Kafka'ya gönderilemeyen outbox denemeleri").register(meters);
    }

    /** @return bu çağrıda başarıyla yayınlanan mesaj sayısı */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = events.lockNextBatch(batchSize);
        int published = 0;
        for (OutboxEvent event : batch) {
            Span span = startPublishSpan(event);
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                // Key = aggregate id (transfer id): aynı transferin mesajları hep aynı partition'a düşer.
                // Gönderim, mesajı üreten isteğin span'ının çocuğu olarak yapılır: KafkaTemplate'in producer span'ı ve
                // Kafka header'ına yazdığı traceparent bu zincire bağlanır, tüketici aynı trace'te devam eder.
                kafka.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                event.markPublished();
                published++;
                publishedCounter.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                span.error(e);
                event.recordFailure("Yayın kesildi (thread interrupt)");
                failedCounter.increment();
                break;
            } catch (Exception e) {
                span.error(e);
                event.recordFailure(describe(e));
                failedCounter.increment();
                log.warn("Outbox mesajı {} ({}) Kafka'ya gönderilemedi (deneme {}): {}",
                        event.getId(), event.getTopic(), event.getAttempts(), describe(e));
                break;
            } finally {
                span.end();
            }
        }
        if (published > 0) {
            log.debug("{} outbox mesajı yayınlandı", published);
        }
        return published;
    }

    /** Mesajın saklanan trace context'inden (yoksa yeni) bir "outbox publish" span'ı başlatır. */
    private Span startPublishSpan(OutboxEvent event) {
        Span.Builder builder = null;
        if (event.getTraceparent() != null) {
            builder = propagator.extract(Map.of("traceparent", event.getTraceparent()), (c, key) -> c.get(key));
        }
        if (builder == null) {
            builder = tracer.spanBuilder();
        }
        return builder.name("outbox publish")
                .kind(Span.Kind.PRODUCER)
                .tag("outbox.topic", event.getTopic())
                .tag("outbox.event_type", event.getEventType())
                .tag("outbox.aggregate_id", event.getAggregateId())
                .start();
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
