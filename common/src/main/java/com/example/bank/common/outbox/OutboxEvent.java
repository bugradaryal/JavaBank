package com.example.bank.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbox kaydı. Her servis kendi veritabanındaki outbox_event tablosuna yazar; hangi topic'e gideceği satırda tutulur,
 * böylece aynı mekanizma hem olayları (transfer-events) hem komutları hem cevapları taşır.
 *
 * <p>id uygulama tarafında üretilir (payload içinde de taşınır). Persistable, Spring Data'ya "bu kayıt yeni"
 * bilgisini verir; yoksa id dolu olduğu için save() önce gereksiz bir SELECT (merge) yapardı.
 */
@Entity
@Table(name = "outbox_event")
@Getter
public class OutboxEvent implements Persistable<UUID> {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    /** Kafka mesaj key'i olarak kullanılır: aynı aggregate'in mesajları aynı partition'a düşer. */
    @Column(name = "aggregate_id", nullable = false, length = 60)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /**
     * Mesajı üreten isteğin W3C trace context'i (traceparent). Outbox mesajı farklı bir thread'de, sonradan
     * yayınlandığı için izleme zincirinin kopmaması bu alanla sağlanır; relay yayın span'ını buna bağlar.
     */
    @Column(length = 80)
    private String traceparent;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean newEntity = true;

    protected OutboxEvent() {
        // JPA
    }

    public static OutboxEvent create(UUID id, String topic, String aggregateType, String aggregateId,
                                     String eventType, String payload) {
        return create(id, topic, aggregateType, aggregateId, eventType, payload, null);
    }

    public static OutboxEvent create(UUID id, String topic, String aggregateType, String aggregateId,
                                     String eventType, String payload, String traceparent) {
        OutboxEvent e = new OutboxEvent();
        e.traceparent = traceparent;
        e.id = id;
        e.topic = topic;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.payload = payload;
        e.createdAt = Instant.now();
        e.attempts = 0;
        return e;
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = error == null ? null
                : (error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH));
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }
}
