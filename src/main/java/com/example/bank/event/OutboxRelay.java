package com.example.bank.event;

import com.example.bank.domain.OutboxEvent;
import com.example.bank.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox'taki yayınlanmamış event'leri Kafka'ya gönderir.
 *
 * <p>Garanti: at-least-once. Gönderim başarılı olup commit başarısız olursa (veya ack kaybolursa) aynı event
 * ikinci kez gönderilir; bu yüzden consumer'lar idempotent olmak zorundadır (bkz. NotificationService).
 *
 * <p>Sıra: batch seq sırasıyla işlenir ve ilk hatada durur; böylece Kafka kapalıyken sıradaki event'ler
 * atlanıp sırası bozulmaz. Kafka dönünce kaldığı yerden devam eder.
 *
 * <p>Bedel: Kafka ack'ini beklerken DB transaction'ı (ve satır kilitleri) açık kalır. Batch küçük
 * ve timeout kısa tutulur; çok yüksek hacimde bu bölüm Debezium gibi bir CDC çözümüyle değiştirilebilir.
 *
 * <p>Ayrı bir bean: @Transactional proxy ile çalışır, scheduler'dan bu sınıfa dışarıdan çağrı yapılır.
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository events;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxRelay(OutboxEventRepository events,
                       KafkaTemplate<String, String> kafka,
                       @Value("${app.kafka.topics.transfer-events}") String topic,
                       @Value("${app.outbox.batch-size:50}") int batchSize,
                       @Value("${app.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.events = events;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    /** @return bu çağrıda başarıyla yayınlanan event sayısı */
    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = events.lockNextBatch(batchSize);
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                // Key = transfer id: aynı transferin event'leri hep aynı partition'a düşer.
                kafka.send(topic, event.getAggregateId(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                event.markPublished();
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("Yayın kesildi (thread interrupt)");
                break;
            } catch (Exception e) {
                event.recordFailure(describe(e));
                log.warn("Outbox event {} Kafka'ya gönderilemedi (deneme {}): {}",
                        event.getId(), event.getAttempts(), describe(e));
                break;
            }
        }
        if (published > 0) {
            log.debug("{} outbox event'i yayınlandı", published);
        }
        return published;
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
