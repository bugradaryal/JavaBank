package com.example.bank;

import com.example.bank.domain.Currency;
import com.example.bank.domain.Customer;
import com.example.bank.domain.Role;
import com.example.bank.repository.AccountRepository;
import com.example.bank.repository.CustomerRepository;
import com.example.bank.repository.LedgerEntryRepository;
import com.example.bank.repository.TransferRepository;
import com.example.bank.security.AuthUser;
import com.example.bank.service.AccountService;
import com.example.bank.service.TransferService;
import com.example.bank.web.dto.AccountResponse;
import com.example.bank.web.dto.MoneyRequest;
import com.example.bank.web.dto.OpenAccountRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gerçek PostgreSQL ile çalışan entegrasyon testlerinin tabanı. Docker gerekir.
 * Container tüm test sınıfları için bir kez başlatılır (Spring context cache'i ile uyumlu olsun diye).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Kafka kesinti testinde publisher'ın çabuk pes edip tekrar denemesi için kısa timeout'lar.
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "5000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "3000");
        registry.add("app.outbox.poll-interval-ms", () -> "200");
        registry.add("app.outbox.send-timeout-ms", () -> "8000");
    }

    protected static final AuthUser TELLER = new AuthUser(0L, "it-teller", Role.TELLER, null);

    @Autowired
    protected CustomerRepository customers;
    @Autowired
    protected AccountRepository accounts;
    @Autowired
    protected LedgerEntryRepository ledger;
    @Autowired
    protected TransferRepository transferRepository;
    @Autowired
    protected AccountService accountService;
    @Autowired
    protected TransferService transferService;

    /** Yeni müşteri + TRY hesabı açar, amount > 0 ise gişeden yatırır. Hesap id'sini döner. */
    protected Long newAccount(String initialDeposit) {
        Customer c = customers.save(new Customer("Test " + UUID.randomUUID(),
                UUID.randomUUID() + "@test.local", null));
        AccountResponse acc = accountService.open(new OpenAccountRequest(Currency.TRY, c.getId()), TELLER);
        BigDecimal amount = new BigDecimal(initialDeposit);
        if (amount.signum() > 0) {
            transferService.deposit(TELLER, acc.id(), new MoneyRequest(amount, "seed"),
                    "seed-" + UUID.randomUUID());
        }
        return acc.id();
    }

    protected BigDecimal balance(Long accountId) {
        return accounts.findById(accountId).orElseThrow().getBalance();
    }

    /** Sistemdeki toplam para (kasa hesapları dahil) hiçbir zaman değişmez: toplam bakiye 0. */
    protected void assertGlobalInvariant() {
        assertThat(accounts.totalBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Hesabın bakiyesi, ledger kayıtlarından türetilen değere eşit olmalı. */
    protected void assertLedgerMatchesBalance(Long accountId) {
        assertThat(ledger.netForAccount(accountId)).isEqualByComparingTo(balance(accountId));
    }

    /** Tüm görevleri aynı anda (start kapısı ile) başlatır ve sonuçları toplar. */
    protected <T> List<T> runConcurrently(int threads, List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
