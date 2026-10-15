package com.example.bank.notification.messaging;

import com.example.bank.common.saga.SagaTopics;
import com.example.bank.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka'dan gelen transfer event'lerini bildirim servisine iletir.
 * Hata olursa exception fırlatılır: KafkaConfig'deki error handler 3 kez tekrar dener, olmazsa DLT'ye atar.
 * Offset, metod hatasız dönünce commit edilir; yani teslim garantisi at-least-once, işleme etkisi exactly-once
 * (idempotency sayesinde).
 */
@Component
public class TransferEventConsumer {

    public static final String LISTENER_ID = "notification-transfer-events";

    private final NotificationService notificationService;

    public TransferEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(id = TransferEventConsumer.LISTENER_ID, idIsGroup = false, groupId = NotificationService.CONSUMER,
            topics = SagaTopics.TRANSFER_EVENTS)
    public void onTransferEvent(String payload) {
        notificationService.handle(payload);
    }
}
