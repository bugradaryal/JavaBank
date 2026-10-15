package com.example.bank.transfer;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Saga orkestratörü. Hesap verisine dokunmaz; account-service ile sadece Kafka üzerinden konuşur. */
@SpringBootApplication(scanBasePackages = {"com.example.bank.transfer", "com.example.bank.common"})
@EntityScan({"com.example.bank.transfer.domain", "com.example.bank.common.outbox"})
@EnableJpaRepositories({"com.example.bank.transfer.repository", "com.example.bank.common.outbox"})
public class TransferApplication {

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(TransferApplication.class)
                .properties("spring.config.name=transfer-service");
    }

    public static void main(String[] args) {
        builder().run(args);
    }
}
