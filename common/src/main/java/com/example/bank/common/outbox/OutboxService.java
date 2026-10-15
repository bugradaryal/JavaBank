package com.example.bank.common.outbox;

import com.example.bank.common.kafka.JsonCodec;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository events;
    private final JsonCodec codec;
    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxService(OutboxEventRepository events, JsonCodec codec, Tracer tracer, Propagator propagator) {
        this.events = events;
        this.codec = codec;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * MANDATORY: çağıranın transaction'ı olmak zorunda. Outbox'ın bütün amacı mesajın iş verisiyle
     * AYNI transaction'da yazılmasıdır; transaction dışında çağrılırsa hata verir, sessizce yanlış çalışmaz.
     *
     * @return üretilen mesaj id'si
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(String topic, String aggregateType, String aggregateId, String eventType, Object payload) {
        UUID id = UUID.randomUUID();
        enqueue(id, topic, aggregateType, aggregateId, eventType, payload);
        return id;
    }

    /** Mesaj id'sinin payload'ın içinde de taşındığı durumlar için (id önceden üretilir). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID id, String topic, String aggregateType, String aggregateId, String eventType,
                        Object payload) {
        events.save(OutboxEvent.create(id, topic, aggregateType, aggregateId, eventType, codec.write(payload),
                currentTraceparent()));
    }

    /** Şu an çalışan span'ın trace context'i (yoksa null). Mesajla birlikte saklanır, yayın sırasında geri bağlanır. */
    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, (c, key, value) -> c.put(key, value));
        return carrier.get("traceparent");
    }
}
