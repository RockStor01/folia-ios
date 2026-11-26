#!/usr/bin/env bash
# 定時巡檢入口，建議掛 cron：
#   0 4 * * * /path/to/run-check.sh >> /var/log/lossless-check.log 2>&1
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MUSIC_DIR="${MUSIC_DIR:-$ROOT/music/downloads}"
QUARANTINE="${QUARANTINE:-$ROOT/music/quarantine}"
REPORT="${REPORT:-$ROOT/reports/lossless-$(date +%F).csv}"

mkdir -p "$QUARANTINE" "$(dirname "$REPORT")"

python3 "$ROOT/scripts/lossless_check.py" "$MUSIC_DIR" \
  --move "$QUARANTINE" \
  --report "$REPORT" \
  --jobs "$(nproc 2>/dev/null || echo 4)"
