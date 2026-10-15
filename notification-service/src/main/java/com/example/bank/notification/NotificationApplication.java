package com.example.bank.notification;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Bildirim servisinin outbox'ı yoktur (sadece tüketir), bu yüzden common'ın outbox paketini TARAMAZ;
 * aksi halde outbox_event tablosu ve zamanlayıcıları gerektirirdi.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.bank.notification", "com.example.bank.common.error",
        "com.example.bank.common.security", "com.example.bank.common.config", "com.example.bank.common.kafka",
        "com.example.bank.common.web"})
@EntityScan("com.example.bank.notification.domain")
@EnableJpaRepositories("com.example.bank.notification.repository")
public class NotificationApplication {

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(NotificationApplication.class)
                .properties("spring.config.name=notification-service");
    }

    public static void main(String[] args) {
        builder().run(args);
    }
}
