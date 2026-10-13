package com.example.bank.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    private static final int PARTITIONS = 3;

    @Bean
    public NewTopic transferEventsTopic(@Value("${app.kafka.topics.transfer-events}") String name) {
        return TopicBuilder.name(name).partitions(PARTITIONS).replicas(1).build();
    }

    /** Dead letter topic: işlenemeyen mesajlar kaybolmaz, buraya düşer. Aynı partition sayısı şart (aynı numaraya yazılır). */
    @Bean
    public NewTopic transferEventsDeadLetterTopic(@Value("${app.kafka.topics.transfer-events}") String name) {
        return TopicBuilder.name(name + ".DLT").partitions(PARTITIONS).replicas(1).build();
    }

    /**
     * Spring Boot, CommonErrorHandler tipindeki bu bean'i listener container'a otomatik bağlar.
     * Geçici hata (ör. DB anlık erişilemez): 1 sn arayla 3 tekrar. Hâlâ başarısızsa DLT'ye.
     * Bozuk mesaj (IllegalArgumentException): tekrar denemenin anlamı yok, doğrudan DLT'ye.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(template), new FixedBackOff(1000L, 3L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
