#!/usr/bin/env bash
# Kaos testi (Linux/macOS): yük altındayken bir servisi/altyapıyı çökertir, sonra k6'nın otomatik doğrulamasını bekler.
# Ön koşul: docker compose --profile apps up -d --build
# Örnek:    ./chaos.sh kill-account [RATE] [DURATION] [FAULT_AT] [DOWN_SECONDS]
set -euo pipefail
FAULT="${1:?kullanım: chaos.sh <kill-account|kill-transfer|kill-notification|kill-all|rolling|pause-kafka|stop-kafka|restart-postgres>}"
RATE="${2:-30}"; DURATION="${3:-3m}"; FAULT_AT="${4:-75}"; DOWN="${5:-20}"; USERS="${USERS:-100}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
log() { echo "[$(date +%H:%M:%S)] $*"; }

declare -A CONTAINER=( [account]=bank-account-service [transfer]=bank-transfer-service [notification]=bank-notification-service )
declare -A PORT=( [account]=8081 [transfer]=8082 [notification]=8083 )

wait_healthy() {
  local t0=$SECONDS
  until curl -fs "http://localhost:${PORT[$1]}/actuator/health" 2>/dev/null | grep -q '"UP"'; do sleep 1; done
  log "$1 yeniden sağlıklı (başlatmadan $((SECONDS - t0)) sn sonra)"
}
kill_svc()  { log "SIGKILL -> ${CONTAINER[$1]}"; docker kill "${CONTAINER[$1]}" >/dev/null; }
start_svc() { log "başlatılıyor -> ${CONTAINER[$1]}"; docker start "${CONTAINER[$1]}" >/dev/null; wait_healthy "$1"; }

for c in bank-account-service bank-transfer-service bank-notification-service bank-postgres bank-kafka; do
  [ -n "$(docker ps --filter "name=$c" --filter status=running -q)" ] || { echo "$c çalışmıyor. Önce: docker compose --profile apps up -d --build"; exit 1; }
done

mkdir -p "$ROOT/load-tests/results"
log "k6 başlatılıyor (hız=$RATE/sn, süre=$DURATION, CHAOS=1)"
( cd "$ROOT/load-tests" && k6 run -e SCENARIO=steady -e RATE="$RATE" -e DURATION="$DURATION" -e USERS="$USERS" -e CHAOS=1 \
    --summary-export "results/chaos-$FAULT-$(date +%Y%m%d-%H%M%S).json" transfers.js ) &
K6_PID=$!

log "arıza öncesi bekleniyor ($FAULT_AT sn): kurulum + yük başlangıcı"
sleep "$FAULT_AT"

case "$FAULT" in
  kill-account)      kill_svc account;      sleep "$DOWN"; start_svc account ;;
  kill-transfer)     kill_svc transfer;     sleep "$DOWN"; start_svc transfer ;;
  kill-notification) kill_svc notification; sleep "$DOWN"; start_svc notification ;;
  kill-all)          for s in account transfer notification; do kill_svc $s; done; sleep "$DOWN"
                     for s in account transfer notification; do start_svc $s; done ;;
  rolling)           for s in account transfer notification; do kill_svc $s; sleep "$DOWN"; start_svc $s; sleep 10; done ;;
  pause-kafka)       log "Kafka donduruluyor"; docker pause bank-kafka >/dev/null; sleep "$DOWN"; docker unpause bank-kafka >/dev/null; log "Kafka devam ediyor" ;;
  stop-kafka)        log "Kafka durduruluyor"; docker stop bank-kafka >/dev/null; sleep "$DOWN"; docker start bank-kafka >/dev/null; log "Kafka başlatıldı" ;;
  restart-postgres)  log "PostgreSQL yeniden başlatılıyor"; docker restart bank-postgres >/dev/null; log "PostgreSQL döndü" ;;
  *) echo "bilinmeyen arıza: $FAULT"; kill $K6_PID; exit 1 ;;
esac

log "arıza giderildi; k6 doğrulamaları yapıyor (saga boşalması, Σ bakiye, clearing, mutabakat)..."
if wait "$K6_PID"; then
  echo "SONUÇ: GEÇTİ. '$FAULT' sonrasında para kaybı yok, saga'lar bitti, mutabakat temiz."
else
  echo "SONUÇ: BAŞARISIZ. Yukarıdaki 'DOĞRULAMA' satırlarına bak."; exit 1
fi
