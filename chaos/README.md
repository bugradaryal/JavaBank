# Kaos testleri

Ön koşul: Docker, k6, `docker compose --profile apps up -d --build`.

```
.\chaos.ps1 -Fault <senaryo> [-Rate 30] [-Duration 3m] [-FaultAt 75] [-DownSeconds 20] [-Users 100]
./chaos.sh <senaryo> [RATE] [DURATION] [FAULT_AT] [DOWN_SECONDS]
```

Akış: k6 `steady` yükü arka planda başlar (CHAOS=1) → `FaultAt` sn sonra arıza → servis geri başlar, `/actuator/health` UP beklenir
(toparlanma süresi yazılır) → k6 biter, teardown: saga'ların boşalması, Σ bakiye = 0, clearing = 0, hesap defteri ve çapraz servis mutabakatı.
Çıkış kodu 0 = geçti. Sonuç JSON'u `load-tests/results/` altına yazılır.

| Senaryo | Ne olur / beklenen |
|---|---|
| kill-account | Komutlar Kafka'da birikir; servis dönünce işlenir. operation_log tekrarları engeller. Gönderici tarafta outbox birikir (OutboxBacklog alarmı olabilir). |
| kill-transfer | Cevaplar Kafka'da bekler; dönünce saga ilerler. Yarım kalan adımı recovery job yeniden gönderir. Yeni transfer istekleri bu sürede hata alır (k6 `transfers_not_accepted` artar, CHAOS'ta eşik yok). |
| kill-notification | Çekirdek akış etkilenmez; bildirimler sonradan işlenir (idempotent consumer). |
| kill-all / rolling | Hepsi/sırayla; yalnızca veri tabanı ve Kafka durumundan toparlanma. |
| pause-kafka / stop-kafka | Outbox yayın hatası sayar, mesajlar tabloda kalır; Kafka dönünce sırayla gider (at-least-once, tekrarlar idempotent). |
| restart-postgres | Bağlantılar kopar; havuz yeniden bağlanır. Beklenen gürültü: `kafka.dlt.published` > 0 olabilir (üstel bekleme ~1 dk, daha uzun kesintide DLT); recovery job adımı yeniden gönderir, bu alarm bilgi amaçlı incelenmeli. |

Bilinen sınırlar: zaman aşımı sonrası geç gelen cevap için fencing yok; clearing hesabı tek sıcak satırdır; HTTP isteği sırasında servis ölürse istemci hata görür, `Idempotency-Key` ile tekrar dener.
