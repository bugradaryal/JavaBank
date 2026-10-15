# bank-platform (Faz 5: ölçüm ve kaos)

Faz 2'deki tek parça banka API'sinin servislere bölünmüş hali. Java 21, Spring Boot 3.3, Kafka, PostgreSQL.

| Servis | Port | Veritabanı | Görev |
|---|---|---|---|
| account-service | 8081 | accountdb | müşteri, hesap, bakiye, ledger, giriş (JWT) |
| transfer-service | 8082 | transferdb | transfer API'si, saga orkestratörü |
| notification-service | 8083 | notificationdb | transfer sonucunu tüketir, bildirim üretir |
| common | - | - | ortak sözleşmeler (komut/cevap/olay), outbox, JWT, hata modeli |

Her servis **kendi veritabanına** sahiptir; başka servisin tablosuna erişmez, FK yoktur. Servisler sadece Kafka ile konuşur.

## Saga (orkestrasyon)

transfer-service orkestratördür. Akış:

```
POST /api/transfers  ->  transfer kaydı (STARTED) + DEBIT komutu   (tek transaction, outbox)
account-service: kaynak -> CLEARING hesabı   -> cevap
transfer-service: DEBITED  + CREDIT komutu
account-service: CLEARING -> hedef hesap     -> cevap
transfer-service: COMPLETED + TransferEvent  -> notification-service
```

Hedef hesap reddederse (kapalı, para birimi farklı vb.): `COMPENSATING` -> REFUND komutu (CLEARING -> kaynak) -> `COMPENSATED`.
İlk adım reddedilirse (yetersiz bakiye): `FAILED`, hiç para hareket etmemiştir.

**Neden orkestrasyon, koreografi değil:** adım sırası ve telafi kuralı tek yerde yazılı; "bu transfer şu an nerede?" sorusunun cevabı
tek satırda (`saga_state`); takılan saga bulunup kurtarılabilir. Koreografide bu bilgi servislerin olaylarına dağılır, para gibi kritik
bir akışta izlenebilirlik hız kazancından önemlidir. Bedeli: orkestratör merkezi bir bileşendir (ama durumu DB'de olduğundan çökünce kaldığı yerden devam eder).

**Para hiçbir an "havada" değil:** CLEARING (askı) hesabı sayesinde her commit'te tüm bakiyelerin toplamı 0'dır.
Saga'lar bittiğinde clearing de 0'dır. `GET /api/admin/ledger-summary` (account-service) ikisini de gösterir.

## Dayanıklılık mekanizmaları

- **Transactional outbox:** durum değişimi ve gidecek mesaj aynı DB transaction'ında yazılır (çift yazma sorunu yok).
- **Idempotent komut işleme:** account-service her komutu `operation_log` (`transferId:ADIM`) ile bir kez uygular; tekrar gelirse kayıtlı cevabı yeniden yollar.
- **Recovery job:** bir adım `app.saga.step-timeout-ms` (15 sn) cevapsız kalırsa orkestratör o adımın komutunu yeniden gönderir.
- **State machine:** beklenmeyen/tekrar eden/eski cevaplar yok sayılır.
- **DLT + retry:** işlenemeyen mesaj 3 deneme sonra `<topic>.DLT`'ye gider.
- **Idempotency-Key** (HTTP) ve **pessimistic lock** (hesaplar id sırasıyla kilitlenir, deadlock yok).

## Çalıştırma

```
docker compose up -d                 # postgres (3 veritabanı) + kafka
mvn clean verify                     # birim testleri + SagaSystemIT (Docker gerekir)
mvn -pl account-service spring-boot:run
mvn -pl transfer-service spring-boot:run
mvn -pl notification-service spring-boot:run
```

Swagger: `http://localhost:8081/swagger-ui.html` (account), `8082` (transfer), `8083` (notification).
Giriş: `POST /api/auth/login` (admin `Admin123!`, teller `Teller123!`; production'da env ile değiştir). Token üç serviste de geçerlidir.
Transfer: `POST /api/transfers` (`Idempotency-Key` zorunlu, `?wait=10000` sonuçlanmasını bekler); sonuç `GET /api/transfers/{id}`.

## Testler

`integration-tests/SagaSystemIT` (üç servis aynı JVM'de, gerçek Postgres + Kafka, Testcontainers):

- mutlu yol + bildirimler,
- yetersiz bakiye (FAILED, para hareket etmez),
- hedef hesap kapalı -> telafi (COMPENSATED, para geri gelir),
- **account-service saga ortasında çöküyor** -> para clearing'de güvende, yeniden başlayınca saga tamamlanır,
- **transfer-service adımlar arasında çöküyor** -> DB'deki durumdan devam eder,
- takılan adım tekrar gönderilir, çift komut **bir kez** uygulanır,
- aynı Idempotency-Key ikinci saga başlatmaz,
- başkasının hesabından harcama reddedilir.

Her testin sonunda: Σ bakiye = 0 ve clearing = 0.

## Bilinen sınırlar

- Henüz **derlenmedi / çalıştırılmadı** (geliştirme ortamında Maven ve Docker yoktu): ilk `mvn verify`'da küçük hatalar çıkabilir.
- Cevap hiç gelmezse saga sonsuza dek tekrar dener (abort/fencing yok).
- Clearing hesabı para birimi başına tek satırdır: yüksek yükte sıcak satır (Faz 5'te ölçülecek).
- Telafi (iade) kapalı hesaba bile yapılır.
- Testcontainers + çok yeni Docker (29+) sürümlerinde API uyumsuzluğu olabilir; olursa Testcontainers sürümünü yükselt.
- JWT HMAC ortak anahtar; gerçekte RS256 + secret manager.

## Metrikler (Faz 4.1)

`docker compose up -d` artık Prometheus (http://localhost:9090) ve Grafana (http://localhost:3000, admin/admin; "Bank Platform" dashboard'u hazır gelir) da başlatır.
Servisler host'ta çalışır; Prometheus `host.docker.internal:8081-8083/actuator/prometheus` adreslerini her 10 sn scrape eder.

**İş metrikleri** (hepsi `application`/`service` etiketli):

| Metrik | Anlamı |
|---|---|
| `ledger_total_balance` | Tüm bakiyelerin toplamı. Daima 0 olmalı |
| `ledger_clearing_balance{currency}` | Yoldaki para. Saga'lar bitince 0 |
| `saga_in_flight{state}` | Duruma göre transfer sayısı |
| `saga_completed_total{outcome,type}` | completed / failed / compensated |
| `saga_duration_seconds` | Transferin başlangıcından sonucuna süre (histogram) |
| `saga_step_retries_total{step}` | Recovery job'ın yeniden gönderdiği adımlar |
| `account_commands_total{step,result}` | success / rejected / duplicate / error |
| `outbox_pending`, `outbox_oldest_pending_age_seconds` | Outbox birikmesi |
| `outbox_published_total`, `outbox_publish_failures_total` | Yayın sayaçları |
| `kafka_dlt_published_total{source_topic}` | DLT'ye düşen mesajlar |
| `notification_events_total{result}` | processed / duplicate |

Hazır teknik metrikler: HTTP (histogram), JVM, Hikari bağlantı havuzu, Kafka consumer lag.

**Alarm kuralları** (`docker/prometheus/alerts.yml`): LedgerInvariantBroken, ClearingNotDrained, SagaStuck, SagaRetriesHigh,
SagaCompensationSpike, DeadLetterMessages, OutboxBacklog, OutboxPublishFailures, ServiceDown, HighServerErrorRate, SlowRequests,
DbConnectionPoolSaturated. Prometheus arayüzünde Alerts sekmesinde görünür (bildirim kanalı bağlı değil).

Notlar: `/actuator/prometheus` ve `/actuator/health` kimlik doğrulamasız açıktır (Prometheus token taşımaz); production'da bu uçlar iç ağda kalmalıdır.

## Dağıtık izleme (Faz 4.2)

Tek bir transfer HTTP isteğinden başlar; Kafka üzerinden üç serviste, 3-4 hop boyunca ilerler. Tek bir **trace** olarak görünür:

```
POST /api/transfers (transfer-service) -> outbox publish -> account-service: debit -> outbox publish
  -> transfer-service: reply -> outbox publish -> account-service: credit -> ... -> notification-service
```

- **Stack:** Micrometer Tracing -> OpenTelemetry -> OTLP/HTTP -> **Tempo** -> Grafana. `docker compose up -d` Tempo'yu da başlatır (4318: OTLP, 3200: Tempo API).
- **Kafka üzerinden taşıma:** `spring.kafka.template/listener.observation-enabled`. Producer, trace context'ini (W3C `traceparent`) mesaj header'ına yazar; tüketici aynı trace'te devam eder.
- **Outbox'ta kopmayan iz:** mesaj, onu üreten isteğin thread'inde değil, outbox publisher'ında (başka thread, sonra) yayınlanır; normalde iz burada kopardı.
  Bu yüzden `outbox_event.traceparent` kolonu mesajla birlikte yazılır, publisher "outbox publish" span'ını bu context'in çocuğu olarak açar.
- **İş etiketleri:** span'larda `transfer.id`, `saga.state`, `saga.step`, `command.result` var.
  Grafana -> Explore -> Tempo -> TraceQL: `{ span.transfer.id = "42" }` ile bir transferin bütün izi bulunur.
- **Log korelasyonu:** her log satırı `[servis, traceId, spanId]` taşır. Bir trace'in traceId'si ile loglarda arama yapılır (merkezi log toplama bu fazda yok: Loki/ELK ayrı bir adım).
- **Örnekleme:** dev'de `TRACING_SAMPLING=1.0`; production'da `0.05-0.1` önerilir. Entegrasyon testlerinde 0.0 (Tempo yok, context yine yayılır).
- Tempo ayakta değilse servisler çalışmaya devam eder, sadece export uyarısı loglar.

**Ne işe yarar (örnek):** "Bu transfer neden 8 saniye sürdü?" -> trace'te hangi adımın (debit komutu Kafka'da mı bekledi, outbox mu geç yayınladı, credit mi yavaş)
uzadığı süre çubuklarında görünür; `saga_duration_seconds` yavaşlığı gösterir, trace nedenini gösterir.

## Faz 4 sonrası sınırlar

- Henüz **derlenmedi / çalıştırılmadı**. İlk çalıştırmada Boot/OTel sürüm uyumsuzlukları veya küçük derleme hataları çıkabilir.
- Actuator uçları kimlik doğrulamasız; production'da iç ağda tutulmalı.
- Alarmlar yalnızca Prometheus'ta görünür (Alertmanager/Slack bağlantısı yok).
- Merkezi log toplama (Loki/ELK) yok; sadece traceId korelasyonu hazır.

## Mutabakat / reconciliation (Faz 5.1)

Testler "bu senaryolarda para kaybolmadı"yı gösterir; mutabakat ise **canlı sistemde sürekli** "para hâlâ doğru mu?" diye sorar.
İki katman:

**1) account-service: kendi muhasebesi** (`LedgerReconciliationService`, 5 dakikada bir + `GET /api/admin/reconciliation`).
Tek bir REPEATABLE READ snapshot'ında:

| Kontrol | Anlamı |
|---|---|
| `TOTAL_NOT_ZERO` | Tüm bakiyelerin toplamı 0 değil |
| `BALANCE_LEDGER_MISMATCH` | Bir hesabın bakiyesi ledger'daki CREDIT-DEBIT toplamına eşit değil |
| `ENTRY_WITHOUT_OPERATION` | Başarılı işlem kaydı olmayan ledger kaydı |
| `OPERATION_ENTRY_COUNT` | Başarılı işlem tam 2 ledger kaydı üretmemiş |
| `OPERATION_NOT_BALANCED` | Bir işlemin ledger kayıtları sıfıra toplanmıyor |

**2) Servisler arası** (`ReconciliationService`, transfer-service, dakikada bir): bitmiş her transferin sonucu, account-service'in işlem kayıtlarıyla karşılaştırılır:

| transfer sonucu | account-service'te beklenen |
|---|---|
| COMPLETED | DEBIT başarılı, CREDIT başarılı, REFUND yok |
| COMPENSATED | DEBIT başarılı, CREDIT reddedilmiş, REFUND başarılı |
| FAILED | DEBIT reddedilmiş, CREDIT ve REFUND yok |

Uyuşmazlık `reconciliation_finding` tablosuna yazılır ve bir insan `POST /api/admin/reconciliation/findings/{id}/resolve` diyene kadar açık kalır
(`reconciliation_findings_open` metriği, `ReconciliationMismatch` alarmı). En tehlikeli durum `FAILED_BUT_DEBITED` (başarısız görünen transferde para çıkmış).
Her transfer bir kez kontrol edilir (`reconciled_at`); account-service erişilemezse tur atlanır, sonra tekrar denenir. `POST /api/admin/reconciliation/run` turu elle tetikler.

**Testler:** `ReconciliationEvaluatorTest` (10 birim testi, saf mantık) ve `ReconciliationIT`: sağlıklı sistemde bulgu yok;
veri kasten bozulunca (başarısız transferi "tamamlandı" yapmak, bakiyeye +5 eklemek, işlem kaydı olmayan ledger kaydı eklemek) mutabakat bunu **yakalıyor**.

Not: servisler arası çağrı için transfer-service ortak JWT anahtarıyla kısa ömürlü bir ADMIN token üretir (gösterim için; gerçekte mTLS/servis hesabı).

Sıradaki: 5.2 k6 yük testleri, 5.3 servisleri çökertme senaryoları ve README'ye gerçek sayılar.

## Yük testleri (Faz 5.2)

`load-tests/` altında k6 senaryoları (steady, ramp, spike, hot, idempotency). Her çalıştırma sonunda otomatik olarak
Σ bakiye = 0, clearing = 0, bekleyen saga yok, muhasebe kontrolü ve mutabakat temiz mi diye doğrulanır; biri tutmazsa test başarısızdır.
Ayrıntılar: `load-tests/README.md`. Sıradaki: 5.3 (servisleri yük altında çökertme, Dockerfile'lar, README'ye gerçek sayılar).

## Kaos testleri (Faz 5.3)

Servisleri container olarak çalıştırıp yük altındayken öldürür; sonunda k6 teardown'u para değişmezlerini doğrular.

```
docker compose --profile apps up -d --build        # postgres, kafka, 3 servis, prometheus, tempo, grafana
# (isteğe bağlı) Prometheus container hedefleri için: $env:PROM_CONFIG="./docker/prometheus/prometheus-containers.yml"
cd chaos
.\chaos.ps1 -Fault kill-account                    # Linux/macOS: ./chaos.sh kill-account
```

Senaryolar: `kill-account`, `kill-transfer`, `kill-notification`, `kill-all`, `rolling`, `pause-kafka`, `stop-kafka`, `restart-postgres`.
Ayrıntı ve beklenen davranış: [chaos/README.md](chaos/README.md). `CHAOS=1` modunda gecikme/hız eşikleri kapalıdır;
`invariant_violations`, `reconciliation_findings`, `idempotency_violations` eşikleri açık kalır.

Docker: kökteki `Dockerfile` tek bir çok aşamalı dosyadır (`--build-arg SERVICE=account-service|transfer-service|notification-service`).
Kafka iki listener'a sahiptir: container'lar `kafka:19092`, host `localhost:9092`.
Retry: tüketici hata işleyicisi 1→32 sn üstel bekleme ile ~1 dk dener (DB kesintisi DLT'ye düşürmesin); takılan adımı recovery job zaten yeniden gönderir.
