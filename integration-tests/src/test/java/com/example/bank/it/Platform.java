package com.example.bank.it;

import com.example.bank.account.AccountApplication;
import com.example.bank.notification.NotificationApplication;
import com.example.bank.transfer.TransferApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;

/**
 * Üç servisi aynı JVM'de, gerçek PostgreSQL (her servise ayrı veritabanı) ve gerçek Kafka ile ayağa kaldırır.
 * Servisler tek tek "çökertilip" (context kapatılır) yeniden başlatılabilir: kapatma, süreç ölümünün
 * pratikte aynısıdır (bellekteki hiçbir şey kalmaz, sadece DB ve Kafka'daki veri).
 */
public final class Platform implements AutoCloseable {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
    private static boolean infraStarted;

    private final long stepTimeoutMs;
    private ConfigurableApplicationContext account;
    private ConfigurableApplicationContext transfer;
    private ConfigurableApplicationContext notification;

    /** @param stepTimeoutMs saga adımı bu kadar süre cevapsız kalırsa recovery job komutu yeniden gönderir */
    public Platform(long stepTimeoutMs) {
        this.stepTimeoutMs = stepTimeoutMs;
    }

    public synchronized void start() throws SQLException {
        if (!infraStarted) {
            POSTGRES.start();
            KAFKA.start();
            try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                    POSTGRES.getPassword()); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE accountdb");
                s.execute("CREATE DATABASE transferdb");
                s.execute("CREATE DATABASE notificationdb");
            }
            infraStarted = true;
        }
        startAccount();
        startTransfer();
        startNotification();
    }

    // ---- servis yaşam döngüsü ----

    public void startAccount() {
        account = AccountApplication.builder().run(commonArgs("accountdb"));
    }

    public void startTransfer() {
        List<String> args = new ArrayList<>(List.of(commonArgs("transferdb")));
        args.add("--app.saga.step-timeout-ms=" + stepTimeoutMs);
        args.add("--app.saga.recovery-interval-ms=500");
        // Mutabakat testlerde elle tetiklenir (zamanlayıcı yarışmasın): tur aralığı çok uzun, bekleme payı 0.
        args.add("--app.reconciliation.interval-ms=3600000");
        args.add("--app.reconciliation.initial-delay-ms=3600000");
        args.add("--app.reconciliation.grace-ms=0");
        args.add("--app.account-service.url=" + accountUrl());
        transfer = TransferApplication.builder().run(args.toArray(new String[0]));
    }

    public void startNotification() {
        notification = NotificationApplication.builder().run(commonArgs("notificationdb"));
    }

    /** "Süreç çöktü": context kapanır, bellekteki her şey gider. */
    public void crashAccount() {
        account.close();
        account = null;
    }

    public void crashTransfer() {
        transfer.close();
        transfer = null;
    }

    private static String[] commonArgs(String db) {
        return new String[]{
                "--app.reconciliation.interval-ms=3600000",
                "--app.reconciliation.initial-delay-ms=3600000",
                "--server.port=0",
                "--spring.jmx.enabled=false",
                "--spring.datasource.url=jdbc:postgresql://" + POSTGRES.getHost() + ":"
                        + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--app.outbox.poll-interval-ms=200",
                // Testte Tempo yok: örnekleme kapalı (trace context yine yayılır, hiçbir şey export edilmez).
                "--management.tracing.sampling.probability=0.0"
        };
    }

    // ---- adresler ----

    public String accountUrl() {
        return "http://localhost:" + account.getEnvironment().getProperty("local.server.port");
    }

    public String transferUrl() {
        return "http://localhost:" + transfer.getEnvironment().getProperty("local.server.port");
    }

    // ---- Kafka listener kontrolü: bir adımı deterministik olarak "askıya almak" için ----

    public void pause(ConfigurableApplicationContext ctx, String listenerId) {
        MessageListenerContainer c = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(listenerId);
        c.pause();
        await().atMost(Duration.ofSeconds(30)).until(c::isContainerPaused);
    }

    public void resume(ConfigurableApplicationContext ctx, String listenerId) {
        ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer(listenerId).resume();
    }

    public ConfigurableApplicationContext accountContext() {
        return account;
    }

    public ConfigurableApplicationContext transferContext() {
        return transfer;
    }

    // ---- doğrudan veritabanı okuma (servislerin API'si dışında bağımsız doğrulama) ----

    public long count(String db, String sql, Object... params) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Doğrudan SQL çalıştırır (test verisini kasten bozmak için). Etkilenen satır sayısını döner. */
    public int execute(String db, String sql, Object... params) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + db,
                POSTGRES.getUsername(), POSTGRES.getPassword());
             var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        for (ConfigurableApplicationContext ctx : new ConfigurableApplicationContext[]{notification, transfer, account}) {
            if (ctx != null) {
                ctx.close();
            }
        }
    }
}
