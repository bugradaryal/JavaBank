package com.example.bank.notification;

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

    private final NotificationService notificationService;

    public TransferEventConsumer(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "${app.kafka.topics.transfer-events}", groupId = NotificationService.CONSUMER)
    public void onTransferEvent(String payload) {
        notificationService.handle(payload);
    }
}
