#!/usr/bin/env bash
# Linux/macOS çalıştırıcı. Örnek: ./run.sh steady 50 2m
set -euo pipefail
SCENARIO="${1:-steady}"; RATE="${2:-50}"; DURATION="${3:-2m}"; USERS="${4:-100}"
command -v k6 >/dev/null || { echo "k6 bulunamadı: https://k6.io/docs/get-started/installation/"; exit 1; }
mkdir -p results
k6 run -e SCENARIO="$SCENARIO" -e RATE="$RATE" -e DURATION="$DURATION" -e USERS="$USERS" \
  --summary-export "results/${SCENARIO}-$(date +%Y%m%d-%H%M%S).json" transfers.js
