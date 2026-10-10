#!/usr/bin/env bash
# Runs the app's networking layer against a throw-away REAL backend on a spare port.
# Usage: android/scripts/integration-test.sh [port]   (default 8001)
# Two runs, each on a fresh backend: default settings (BackendContractTest), then ATTEMPT_TTL_SECONDS=4 and
# CHECKOUT_GRACE_MINUTES=0 (TimingContractTest). Exits non-zero if either fails.
# Uses a temp database and photo dir, the real OpenCV face engine, and deletes everything on exit.
# Never prints secrets: SEED_PASSWORD (students) and FACULTY_SEED_PASSWORD (faculty1) are read from
# backend/.env (or the environment) and passed on via env.
set -euo pipefail
PORT="${1:-8001}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID="$(dirname "$HERE")"
BACKEND="$(dirname "$ANDROID")/backend"
TMP="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT

PY="$BACKEND/.venv/bin/python"
[ -x "$PY" ] || { echo "backend venv missing: see backend/README.md" >&2; exit 1; }
[ -f "$BACKEND/models/face_detection_yunet_2023mar.onnx" ] || "$PY" "$BACKEND/scripts/download_models.py"

# Face image: sample from backend/tests/samples, else OpenCV's lena.jpg into the temp dir.
FACE="$BACKEND/tests/samples/lena.jpg"
if [ ! -f "$FACE" ]; then
  FACE="$TMP/lena.jpg"
  curl -fsSL -o "$FACE" https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/lena.jpg
fi

# Reads NAME from the environment, else from backend/.env (quotes stripped). Never echoes the value.
read_setting() {
  local value="${!1:-}"
  [ -n "$value" ] || value="$(grep -E "^$1=" "$BACKEND/.env" | head -1 | cut -d= -f2- | sed -e 's/^["'"'"']//' -e 's/["'"'"']$//')"
  [ -n "$value" ] || { echo "$1 not found" >&2; exit 1; }
  printf '%s' "$value"
}
PASSWORD="$(read_setting SEED_PASSWORD)"
FACULTY_PASSWORD="$(read_setting FACULTY_SEED_PASSWORD)"

# Starts a fresh throw-away backend on $PORT (new database each time). Extra NAME=value arguments become
# settings of that server only. Sets SERVER_PID.
start_backend() {
  local data="$TMP/data-$1"; shift
  mkdir -p "$data"
  (cd "$BACKEND" && env DATABASE_PATH="$data/fieldpass.db" PHOTO_DIR="$data/photos" "$@" "$PY" -m app.seed >/dev/null)
  (cd "$BACKEND" && exec env DATABASE_PATH="$data/fieldpass.db" PHOTO_DIR="$data/photos" "$@" \
    "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port "$PORT" >"$data/server.log" 2>&1) &
  SERVER_PID=$!
  for _ in $(seq 1 40); do
    curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1 && break
    sleep 0.5
  done
  curl -fs "http://127.0.0.1:$PORT/api/v1/health" >/dev/null || { echo "backend did not start" >&2; tail -5 "$data/server.log" >&2; exit 1; }
}

stop_backend() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
  [ -n "$SERVER_PID" ] && wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
}

# Runs one test class against the current backend. Prints the [integration] lines and fails the script if Gradle fails.
run_tests() {
  local test_class="$1"; shift
  local status=0
  (cd "$ANDROID" && env FIELDPASS_API_URL="http://127.0.0.1:$PORT" FIELDPASS_PASSWORD="$PASSWORD" \
    FIELDPASS_FACULTY_PASSWORD="$FACULTY_PASSWORD" FIELDPASS_FACE_IMAGE="$FACE" "$@" \
    ./gradlew testDebugUnitTest --tests "com.fieldpass.student.integration.$test_class" -i) >"$TMP/gradle.log" 2>&1 || status=$?
  cat "$TMP/gradle.log" >>"${INTEGRATION_LOG:-/dev/null}"
  grep -E "\[integration\]|BUILD|tests completed|FAILED|AssertionError|Exception" "$TMP/gradle.log" || true
  [ "$status" -eq 0 ] || { echo "[integration] gradle exited with $status; last lines of its log:" >&2; tail -25 "$TMP/gradle.log" >&2; }
  return "$status"
}

[ -z "${INTEGRATION_LOG:-}" ] || : >"$INTEGRATION_LOG"

# Run 1: default settings (90 s attempts, 15/30 minute windows): the 20 contract steps.
echo "[integration] run 1: default backend settings"
RESULT=0
start_backend default
run_tests BackendContractTest || RESULT=1
stop_backend

# Run 2: short settings so attempt expiry and a missing check-out happen after a few real seconds.
echo "[integration] run 2: ATTEMPT_TTL_SECONDS=4, CHECKOUT_GRACE_MINUTES=0"
start_backend short ATTEMPT_TTL_SECONDS=4 CHECKOUT_GRACE_MINUTES=0
run_tests TimingContractTest FIELDPASS_SHORT_TIMING=1 || RESULT=1
stop_backend

[ "$RESULT" -eq 0 ] && echo "[integration] all runs passed" || echo "[integration] FAILED: see the lines above" >&2
exit "$RESULT"
