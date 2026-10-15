package com.example.bank.account;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Sadece kendi paketini ve ortak (common) paketi tarar: entegrasyon testlerinde üç servis aynı classpath'te
 * bulunur, birbirlerinin bean'lerini görmemeleri gerekir.
 * Yapılandırma dosyası adı sabittir (account-service.yml); aynı JVM'de üç servis yan yana çalışabilsin diye.
 */
@SpringBootApplication(scanBasePackages = {"com.example.bank.account", "com.example.bank.common"})
@EntityScan({"com.example.bank.account.domain", "com.example.bank.common.outbox"})
@EnableJpaRepositories({"com.example.bank.account.repository", "com.example.bank.common.outbox"})
public class AccountApplication {

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(AccountApplication.class)
                .properties("spring.config.name=account-service");
    }

    public static void main(String[] args) {
        builder().run(args);
    }
}
