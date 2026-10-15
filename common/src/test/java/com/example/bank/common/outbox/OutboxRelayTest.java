package com.example.bank.common.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String TOPIC = "account.replies";

    @Mock
    OutboxEventRepository events;
    @Mock
    KafkaTemplate<String, String> kafka;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(events, kafka, new SimpleMeterRegistry(), Tracer.NOOP, Propagator.NOOP, 50, 1000);
    }

    @Test
    void publishesPendingEventsInOrderToTheirOwnTopicAndMarksThemPublished() {
        OutboxEvent e1 = event("1", "topic-a");
        OutboxEvent e2 = event("2", "topic-b");
        when(events.lockNextBatch(50)).thenReturn(List.of(e1, e2));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());

        int published = relay.publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(e1.getPublishedAt()).isNotNull();
        assertThat(e2.getPublishedAt()).isNotNull();
        InOrder order = inOrder(kafka);
        order.verify(kafka).send("topic-a", "1", e1.getPayload());
        order.verify(kafka).send("topic-b", "2", e2.getPayload());
    }

    @Test
    void kafkaFailureLeavesEventPendingAndStopsTheBatchToPreserveOrder() {
        OutboxEvent e1 = event("1", TOPIC);
        OutboxEvent e2 = event("2", TOPIC);
        when(events.lockNextBatch(50)).thenReturn(List.of(e1, e2));
        when(kafka.send(eq(TOPIC), anyString(), anyString())).thenReturn(failed("kafka down"));

        int published = relay.publishBatch();

        assertThat(published).isZero();
        assertThat(e1.getPublishedAt()).isNull();
        assertThat(e1.getAttempts()).isEqualTo(1);
        assertThat(e1.getLastError()).contains("kafka down");
        // 2. event denenmedi bile: sıra bozulmasın
        assertThat(e2.getAttempts()).isZero();
        verify(kafka, times(1)).send(eq(TOPIC), anyString(), anyString());
    }

    @Test
    void failureAfterPartialSuccessKeepsTheAlreadyPublishedOnesPublished() {
        OutboxEvent e1 = event("1", TOPIC);
        OutboxEvent e2 = event("2", TOPIC);
        OutboxEvent e3 = event("3", TOPIC);
        when(events.lockNextBatch(50)).thenReturn(List.of(e1, e2, e3));
        when(kafka.send(eq(TOPIC), anyString(), anyString())).thenReturn(ok(), failed("timeout"));

        int published = relay.publishBatch();

        assertThat(published).isEqualTo(1);
        assertThat(e1.getPublishedAt()).isNotNull();
        assertThat(e2.getPublishedAt()).isNull();
        assertThat(e2.getAttempts()).isEqualTo(1);
        assertThat(e3.getAttempts()).isZero();
    }

    @Test
    void emptyOutboxDoesNothing() {
        when(events.lockNextBatch(50)).thenReturn(List.of());

        assertThat(relay.publishBatch()).isZero();
    }

    private static OutboxEvent event(String aggregateId, String topic) {
        return OutboxEvent.create(UUID.randomUUID(), topic, "Transfer", aggregateId, "TEST",
                "{\"transferId\":" + aggregateId + "}");
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed(String message) {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException(message));
        return future;
    }
}
