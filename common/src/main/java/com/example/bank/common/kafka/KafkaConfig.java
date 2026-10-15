package com.example.bank.common.kafka;

import com.example.bank.common.saga.SagaTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class KafkaConfig {

    private static final int PARTITIONS = 3;

    /**
     * Tüm topic'ler ve dead letter topic'leri. Her servis kendi açılışında eksik olanı oluşturur
     * (zaten varsa dokunmaz), böylece servislerin başlama sırası önemli olmaz.
     * DLT'nin partition sayısı ana topic ile aynı olmalı: DeadLetterPublishingRecoverer aynı partition numarasına yazar.
     */
    @Bean
    public KafkaAdmin.NewTopics sagaTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String name : SagaTopics.all()) {
            topics.add(TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build());
            topics.add(TopicBuilder.name(name + ".DLT").partitions(PARTITIONS).replicas(1).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(new NewTopic[0]));
    }

    /**
     * Spring Boot, CommonErrorHandler tipindeki bu bean'i listener container'lara otomatik bağlar.
     * Geçici hata (ör. DB kısa süre erişilemez): 1, 2, 4, 8, 16, 32 sn arayla yaklaşık 1 dakika tekrar dener, olmazsa DLT'ye.
     * (Bekleme sırasında tüketici thread'i bu mesajda durur; sıra korunur. DLT'ye düşen komut/cevap kaybolmuş sayılmaz:
     * transfer-service'in recovery job'ı takılan adımı yeniden gönderir.)
     * Bozuk mesaj (IllegalArgumentException): tekrar denemenin anlamı yok, doğrudan DLT'ye.
     */
    private static ExponentialBackOff backOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(32_000L);
        backOff.setMaxElapsedTime(63_000L);
        return backOff;
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template);
        // DLT'ye düşen her mesaj sayılır: sıfırdan büyükse işlenemeyen mesaj var demektir (alarm kuralı bunu izler).
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            meters.counter("kafka.dlt.published", "source_topic", record.topic()).increment();
            dlt.accept(record, ex);
        }, backOff());
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
