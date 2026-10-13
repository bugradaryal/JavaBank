package com.example.bank;

import com.example.bank.domain.Notification;
import com.example.bank.domain.OutboxEvent;
import com.example.bank.domain.Transfer;
import com.example.bank.domain.TransferStatus;
import com.example.bank.domain.TransferType;
import com.example.bank.event.OutboxService;
import com.example.bank.notification.NotificationService;
import com.example.bank.notification.ProcessedEventStore;
import com.example.bank.repository.NotificationRepository;
import com.example.bank.repository.OutboxEventRepository;
import com.example.bank.web.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Faz 2 uçtan uca: transfer -> outbox -> Kafka -> consumer -> bildirim.
 * Gerçek PostgreSQL ve gerçek Kafka (Testcontainers) kullanır.
 */
class OutboxNotificationIT extends AbstractIntegrationTest {

    @Autowired
    OutboxEventRepository outbox;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    NotificationService notificationService;
    @Autowired
    ProcessedEventStore processedEvents;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.topics.transfer-events}")
    String topic;

    private TransferRequest eft(Long from, Long to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), TransferType.EFT, "test");
    }

    private Long customerOf(Long accountId) {
        return accounts.findById(accountId).orElseThrow().getCustomerId();
    }

    private OutboxEvent eventOf(Long transferId) {
        List<OutboxEvent> events = outbox.findByAggregateTypeAndAggregateIdIn(
                OutboxService.TRANSFER_AGGREGATE, List.of(String.valueOf(transferId)));
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Test
    void completedTransferIsPublishedThroughOutboxAndNotifiesBothCustomersOnce() {
        Long a = newAccount("500.0000");
        Long b = newAccount("0.0000");

        Transfer t = transferService.transfer(TELLER, eft(a, b, "100.0000"), "ob-" + UUID.randomUUID()).transfer();

        // Transfer ile aynı transaction'da tam bir outbox satırı yazıldı.
        assertThat(eventOf(t.getId()).getEventType()).isEqualTo("TRANSFER_COMPLETED");

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            assertThat(eventOf(t.getId()).getPublishedAt()).isNotNull();
            List<Notification> sent = notifications.findByTransferId(t.getId());
            assertThat(sent).extracting(Notification::getCustomerId)
                    .containsExactlyInAnyOrder(customerOf(a), customerOf(b));
        });
        assertThat(processedEvents.countFor(NotificationService.CONSUMER, eventOf(t.getId()).getId())).isEqualTo(1);
    }

    @Test
    void failedTransferNotifiesOnlyTheSender() {
        Long a = newAccount("50.0000");
        Long b = newAccount("0.0000");

        Transfer t = transferService.transfer(TELLER, eft(a, b, "100.0000"), "ob-" + UUID.randomUUID()).transfer();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(eventOf(t.getId()).getEventType()).isEqualTo("TRANSFER_FAILED");
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            List<Notification> sent = notifications.findByTransferId(t.getId());
            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).getCustomerId()).isEqualTo(customerOf(a));
            assertThat(sent.get(0).getMessage()).contains("Yetersiz bakiye");
        });
    }

    @Test
    void duplicateDeliveryOfTheSameEventDoesNotCreateDuplicateNotifications() throws Exception {
        Long a = newAccount("500.0000");
        Long b = newAccount("0.0000");
        Transfer t = transferService.transfer(TELLER, eft(a, b, "100.0000"), "ob-" + UUID.randomUUID()).transfer();
        await().atMost(Duration.ofSeconds(40))
                .untilAsserted(() -> assertThat(notifications.findByTransferId(t.getId())).hasSize(2));
        OutboxEvent event = eventOf(t.getId());

        // 1) Servis seviyesi: aynı payload ikinci kez verilirse yok sayılır.
        assertThat(notificationService.handle(event.getPayload())).isFalse();

        // 2) Kafka seviyesi: aynı mesaj iki kez daha teslim edilir (at-least-once'ın gerçek hâli).
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get();
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get();

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(notifications.findByTransferId(t.getId())).hasSize(2));
        assertThat(processedEvents.countFor(NotificationService.CONSUMER, event.getId())).isEqualTo(1);
    }

    @Test
    void kafkaOutageLosesNoEventsAndCreatesNoDuplicates() {
        Long a = newAccount("1000.0000");
        Long b = newAccount("0.0000");
        String kafkaId = KAFKA.getContainerId();
        List<Long> transferIds = new ArrayList<>();

        KAFKA.getDockerClient().pauseContainerCmd(kafkaId).exec();
        try {
            for (int i = 0; i < 10; i++) {
                Transfer t = transferService.transfer(TELLER, eft(a, b, "10.0000"),
                        "outage-" + UUID.randomUUID()).transfer();
                transferIds.add(t.getId());
            }
            List<String> aggregateIds = transferIds.stream().map(String::valueOf).toList();

            // Kafka kapalıyken para hareketi yine başarılı; event'ler outbox'ta bekliyor, hiçbiri yayınlanmadı.
            await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                List<OutboxEvent> pending = outbox.findByAggregateTypeAndAggregateIdIn(
                        OutboxService.TRANSFER_AGGREGATE, aggregateIds);
                assertThat(pending).hasSize(10);
                assertThat(pending).allSatisfy(e -> assertThat(e.getPublishedAt()).isNull());
            });
            assertThat(balance(b)).isEqualByComparingTo("100");
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(kafkaId).exec();
        }

        // Kafka geri geldi: hepsi yayınlanır, her transfer için tam iki bildirim (gönderen + alıcı) oluşur.
        List<String> aggregateIds = transferIds.stream().map(String::valueOf).toList();
        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            List<OutboxEvent> events = outbox.findByAggregateTypeAndAggregateIdIn(
                    OutboxService.TRANSFER_AGGREGATE, aggregateIds);
            assertThat(events).hasSize(10);
            assertThat(events).allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
            for (Long id : transferIds) {
                assertThat(notifications.findByTransferId(id)).hasSize(2);
            }
        });

        // Tekrar teslim olsa bile çift işleme yok: sayılar bir süre daha sabit kalır.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            for (Long id : transferIds) {
                assertThat(notifications.findByTransferId(id)).hasSize(2);
            }
        });
        for (OutboxEvent e : outbox.findByAggregateTypeAndAggregateIdIn(OutboxService.TRANSFER_AGGREGATE,
                aggregateIds)) {
            assertThat(processedEvents.countFor(NotificationService.CONSUMER, e.getId())).isEqualTo(1);
        }
        assertGlobalInvariant();
    }
}
