# Bank Core API

Spring Boot 3 / Java 21 / PostgreSQL / Kafka ile banka core API.

- **Faz 1:** tek servis, atomik transfer, ledger, idempotency, JWT.
- **Faz 2:** olay tabanlı yapı: transactional outbox, Kafka, idempotent consumer, retry + dead letter topic.

## Çalıştırma

```bash
docker compose up -d          # PostgreSQL (localhost:5432, bankdb, bank/bank) + Kafka (localhost:9092)
mvn spring-boot:run           # veya IntelliJ'de BankApplication'ı çalıştır
```

Kafka'ya giden event'leri izlemek için:

```bash
docker exec -it bank-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic transfer-events --from-beginning
```

Swagger UI: http://localhost:8080/swagger-ui.html (sağ üstte Authorize ile Bearer token gir)

Hazır personel hesapları (application.yml'den değiştirilebilir):

| Kullanıcı | Şifre       | Rol    |
|-----------|-------------|--------|
| admin     | Admin123!   | ADMIN  |
| teller    | Teller123!  | TELLER |

Müşteri: `POST /api/auth/register` ile kayıt ol, dönen token ile devam et.

## Testler

```bash
mvn test      # birim testleri (Docker gerekmez)
mvn verify    # + entegrasyon testleri (*IT, Testcontainers ile PostgreSQL ve Kafka, Docker gerekir)
```

Önemli entegrasyon testleri:

- `TransferConcurrencyIT`: 100 thread ile eşzamanlı çekim, çapraz transferlerde deadlock olmaması, toplam paranın korunması.
- `TransferIdempotencyIT`: aynı `Idempotency-Key` ile (eşzamanlı dahil) para bir kez hareket eder.
- `OutboxNotificationIT.kafkaOutageLosesNoEventsAndCreatesNoDuplicates`: Kafka container'ı `docker pause` ile dondurulur,
  10 transfer yapılır (başarılı, event'ler outbox'ta bekler), Kafka geri gelince hepsi yayınlanır ve her transfer için
  tam iki bildirim oluşur (kayıp yok, çift işleme yok).

Testcontainers "Docker environment bulunamadı" derse: Docker Desktop açık mı kontrol et. Çok yeni Docker Engine
sürümleriyle eski Testcontainers sürümleri API uyumsuzluğu verebilir; bu durumda Testcontainers sürümünü güncelle.

## Tasarım özeti

- `Account.balance` ve `ledger_entry` aynı transaction'da güncellenir. Her transfer bir DEBIT + bir CREDIT kaydı yazar.
- Para yatırma/çekme, para birimi başına bir **kasa (SYSTEM) hesabı** ile yapılır. Kasa eksiye düşebilir, müşteri hesabı düşemez
  (DB'de `CHECK` ile de korunur). Bu yüzden tüm hesap bakiyelerinin toplamı her zaman 0'dır.
- Transfer: hesaplar `PESSIMISTIC_WRITE` ile **küçük id'den büyüğe** kilitlenir (deadlock önleme). Hesapta `@Version` yedek korumadır.
- `Idempotency-Key` header'ı zorunlu. Aynı key + aynı istek: para bir kez hareket eder, tekrar 200 ve eski sonuç döner.
  Aynı key + farklı istek: 409. Eşzamanlı aynı key: DB unique constraint + kilit sonrası tekrar kontrol.
- Yetersiz bakiye geri alma değil, `FAILED` durumunda kalıcı transfer kaydıdır (422 döner).
- Entity'lerde JPA ilişkisi yok, sadece id alanları var (lazy loading sorunlarını baştan engeller).

### Faz 2: olay tabanlı yapı

```
TransferExecutor (tek DB transaction)
   bakiye + ledger + outbox_event satırı  ──commit──►  PostgreSQL
                                                          │  (OutboxScheduler her 500 ms)
                                                          ▼
                                    OutboxRelay: SELECT ... FOR UPDATE SKIP LOCKED
                                                          │  Kafka'ya gönder, ack bekle, published_at yaz
                                                          ▼
                                            Kafka topic: transfer-events (3 partition)
                                                          │
                                                          ▼
                              TransferEventConsumer → NotificationService (idempotent)
                                   │ hata: 3 tekrar, olmazsa ──► transfer-events.DLT
```

- **Dual write sorunu:** "DB'ye yaz, sonra Kafka'ya gönder" iki ayrı sistemde atomik olamaz; biri patlarsa para gider event gitmez
  (veya tersi). Outbox bunu çözer: event, para hareketiyle aynı transaction'da DB'ye yazılır. Kafka'ya gönderim ayrı ve tekrar denenebilir.
  `OutboxService.enqueueTransferEvent` `Propagation.MANDATORY` kullanır, transaction dışında çağrılırsa hata verir.
- **Garanti: at-least-once.** Gönderim başarılı olup commit/ack kaybolursa event iki kez gönderilebilir. Bu yüzden consumer idempotent.
- **Idempotent consumer:** `processed_event(consumer, event_id)` birincil anahtarı ve `INSERT ... ON CONFLICT DO NOTHING`.
  "İşlendi" kaydı ile bildirimler aynı transaction'da; iş hata verirse kayıt da geri alınır ve event tekrar denenebilir.
  Ek olarak `notification` tablosunda `unique(event_id, customer_id)` ikinci bir emniyet.
- **Sıra:** relay `seq` sırasıyla ilerler ve ilk hatada durur. Kafka kapalıyken sonraki event'ler atlanmaz. Mesaj key'i transfer id'dir.
- **Hata yönetimi:** geçici hatada 1 sn arayla 3 tekrar; bozuk mesaj (IllegalArgumentException) tekrar denenmeden doğrudan `transfer-events.DLT`'ye gider.
- **Bilinen bedel:** relay, Kafka ack'ini beklerken DB transaction'ını açık tutar (batch küçük, timeout kısa). Çok yüksek hacimde
  bu parça CDC (Debezium) ile değiştirilir.
- `GET /api/outbox/stats` (ADMIN): bekleyen/yayınlanan event sayısı ve en eski bekleyenin zamanı. Bekleyen sayı sürekli artıyorsa Kafka'da sorun vardır.

## Endpoint'ler

| Method | Yol | Yetki |
|--------|-----|-------|
| POST | /api/auth/register, /api/auth/login | herkes |
| POST/GET | /api/customers, /api/customers/{id} | personel (GET {id}: müşteri kendi kaydı) |
| PUT | /api/customers/{id} | personel veya kendisi |
| DELETE | /api/customers/{id} | ADMIN |
| POST/GET | /api/accounts, /api/accounts/{id}, /api/accounts/{id}/ledger | müşteri kendi hesapları, personel hepsi |
| POST | /api/accounts/{id}/close | sahibi veya personel |
| POST | /api/accounts/{id}/deposit, /withdraw | TELLER, ADMIN |
| POST | /api/transfers | müşteri kendi hesabından, personel hepsi |
| GET | /api/transfers/{id} | taraflardan biri veya personel |
| GET | /api/audit-logs | ADMIN |
| GET | /api/notifications | müşteri kendi bildirimleri, personel hepsi (`?customerId=`) |
| GET | /api/outbox/stats | ADMIN |
