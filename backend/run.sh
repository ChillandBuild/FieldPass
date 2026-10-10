#!/usr/bin/env bash
# Start the FieldPass backend for LAN use. Usage: ./run.sh [port]
set -euo pipefail
cd "$(dirname "$0")"
PORT="${1:-8000}"
if [ ! -x .venv/bin/python ]; then
  echo "No virtualenv. Run: python3.11 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt" >&2
  exit 1
fi
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || true)"
echo "FieldPass backend: point the phone and dashboard at http://${LAN_IP:-<your-lan-ip>}:${PORT}"
exec .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port "$PORT"
