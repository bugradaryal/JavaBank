# Yük testleri (k6)

Amaç yalnızca "kaç istek/sn?" ölçmek değil; **yük altında paranın doğru kalıp kalmadığını** kanıtlamak. Her çalıştırmanın sonunda
(`teardown`) otomatik olarak şunlar doğrulanır, biri tutmazsa test **başarısız** sayılır:

| Doğrulama | Eşik |
|---|---|
| Bekleyen saga'lar boşaldı (STARTED/DEBITED/COMPENSATING = 0) | `invariant_violations == 0` |
| Σ tüm bakiyeler = 0 | `invariant_violations == 0` |
| Clearing (yoldaki para) = 0 | `invariant_violations == 0` |
| account-service muhasebe kontrolü temiz | `invariant_violations == 0` |
| Servisler arası mutabakatta açık bulgu yok | `reconciliation_findings == 0` |

## Hazırlık

1. `docker compose up -d` (postgres, kafka, prometheus, grafana, tempo).
2. Üç servisi başlat (IntelliJ veya `mvn -pl <servis> spring-boot:run`).
3. k6 kur: `winget install k6` (Windows) ya da https://k6.io/docs/get-started/installation/

## Çalıştırma

```powershell
.\run.ps1 -Scenario smoke                       # önce duman testi: her şey ayakta mı
.\run.ps1 -Scenario steady -Rate 50 -Duration 2m
.\run.ps1 -Scenario ramp   -Rate 50             # kademeli artış: sistemin dayanma noktasını bul
```
(Linux/macOS: `./run.sh steady 50 2m`)

Sonuçlar `results/` altına JSON olarak yazılır. Sunucu tarafını aynı anda Grafana'dan izle: http://localhost:3000 ("Bank Platform").

## Senaryolar

| SCENARIO | Ne yapar | Neyi ölçer |
|---|---|---|
| `smoke` | 1 kullanıcı, 5 transfer | Kurulum doğru mu |
| `steady` | Sabit `RATE` transfer/sn, `DURATION` boyunca, rastgele hesap çiftleri | Normal yükte gecikme ve doğruluk |
| `ramp` | Hız kademeli olarak RATE/4 → 4×RATE | Sistemin dayanma noktası (hangi hızda gecikme/`dropped_iterations` bozulur) |
| `spike` | Ani 5×RATE patlaması, sonra normale dönüş | Ani yük sonrası toparlanma; birikmiş saga'ların boşalma süresi |
| `hot` | Tüm transferler AYNI kaynak hesaptan | Satır kilidi çekişmesi (hot row) |
| `idempotency` | Aynı `Idempotency-Key` ile eşzamanlı 5 istek | Hepsi aynı transferi dönmeli, tek saga çalışmalı |

Ortam değişkenleri: `RATE`, `DURATION`, `USERS` (varsayılan 100), `INITIAL_BALANCE`, `MAX_AMOUNT`, `ACCOUNT_URL`, `TRANSFER_URL`,
`ADMIN_PASSWORD`, `TELLER_PASSWORD`, `E2E_TIMEOUT_S`, `DRAIN_TIMEOUT_S`.

## Metrikler ve eşikler

| Metrik | Anlamı | Eşik (`steady`/`hot`) |
|---|---|---|
| `http_req_duration{name:transfer_create}` | POST /api/transfers yanıt süresi (saga'yı BAŞLATMA süresi) | p95 < 300 ms |
| `http_req_failed{name:transfer_create}` | Hatalı istek oranı | < %1 |
| `saga_e2e_ms` | Transferin başlangıcından COMPLETED'a kadar uçtan uca süre | p95 < 3000 ms |
| `dropped_iterations` | Sistemin kaldıramadığı için hiç başlatılamayan istek | 0 |
| `saga_timeouts` | 30 sn içinde bitmeyen saga | 0 |
| `transfers_unexpected_result` | Beklenmedik FAILED/COMPENSATED | 0 |
| `idempotency_violations` | Aynı key'le birden çok transfer oluşması | 0 |
| `drain_wait_ms` | Yük bittikten sonra bekleyen saga'ların boşalma süresi | bilgi |

`ramp` ve `spike`'ta gecikme eşikleri bilgilendiricidir (amaç sistemi sınırına kadar zorlamak); doğruluk eşikleri yine geçerlidir.

## Beklenen darboğaz (hipotez)

Her transfer, para birimi başına **tek bir clearing hesabına** dokunur (borçlandırmada alacak, alacaklandırmada borç). O satır her transferde
kilitlenir; yani transferler bu noktada seri işlenir ve verim, o satırın commit hızıyla sınırlıdır. `ramp` senaryosunda p95'in kırıldığı
hız büyük olasılıkla bu sınırdır. Çözüm olarak clearing hesabını parçalamak (ör. 16 clearing hesabı, transferId'ye göre seçim) düşünülebilir;
ama önce ölçülmeli.

## Prometheus'a yazmak (isteğe bağlı)

Prometheus `--web.enable-remote-write-receiver` ile başlatılır (docker-compose'ta açık). k6 metriklerini oraya yollamak için:
`K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write k6 run -o experimental-prometheus-rw ...`
