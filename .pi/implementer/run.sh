#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
LABEL="$1"
MESSAGE_FILE="$2"
SESSION="${3:-}"
MODELS=("${IMPLEMENTER_MODEL:-anthropic-account-2/claude-sonnet-5-5}" "anthropic/claude-sonnet-5-5")
MAX_ATTEMPTS="${IMPLEMENTER_MAX_ATTEMPTS:-6}"
RETRY_WAIT_SECONDS="${IMPLEMENTER_RETRY_WAIT_SECONDS:-120}"
LOG_DIR="$ROOT/.pi/implementer/logs"
SESSION_DIR="$ROOT/.pi/implementer/sessions"
mkdir -p "$LOG_DIR" "$SESSION_DIR"
MESSAGE="$(cat "$MESSAGE_FILE")"
MIN_START_KB=$((3 * 1024 * 1024))
MIN_RUN_KB=$((1536 * 1024))
FREE_KB=$(df --output=avail -k / | tail -1)
if [ "$FREE_KB" -lt "$MIN_START_KB" ]; then echo "refusing to start: less than 3 GB free" >&2; exit 4; fi
(
  while sleep 10; do
    if [ "$(df --output=avail -k / | tail -1)" -lt "$MIN_RUN_KB" ]; then
      echo "$(date) disk below 1.5 GB: stopping implementer and gradle" >> "$LOG_DIR/diskguard.log"
      pkill -u "$USER" -f "GradleWrapperMain|GradleDaemon" || true
      pkill -u "$USER" -f "pi-bolt -p --mode json" || true
      exit 0
    fi
  done
) &
WATCHDOG_PID=$!
trap 'kill "$WATCHDOG_PID" 2>/dev/null || true' EXIT
CONTINUE_MESSAGE="Your previous turn was cut off by a provider rate limit. Continue exactly where you stopped; do not redo finished steps."
for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  MODEL="${MODELS[$(((attempt - 1) % ${#MODELS[@]}))]}"
  ARGS=(
    -p --mode json
    --model "$MODEL" --thinking high
    -e "$ROOT/.pi/extensions/implementer-guard.ts"
    --no-mcp -ns -np
    --tools read,bash,edit,write,grep,find,ls
    --append-system-prompt "$ROOT/.pi/implementer/prompt.md"
    --session-dir "$SESSION_DIR"
  )
  if [ -n "$SESSION" ]; then ARGS+=(--session "$SESSION"); fi
  OUT="$LOG_DIR/$LABEL.jsonl"
  [ "$attempt" -gt 1 ] && OUT="$LOG_DIR/$LABEL.retry$attempt.jsonl"
  BEFORE="$(ls -t "$SESSION_DIR"/*.jsonl 2>/dev/null | head -1 || true)"
  pi-bolt "${ARGS[@]}" "$MESSAGE" > "$OUT" 2> "$LOG_DIR/$LABEL.err" || true
  if [ -z "$SESSION" ]; then
    SESSION="$(ls -t "$SESSION_DIR"/*.jsonl 2>/dev/null | head -1 || true)"
    [ "$SESSION" = "$BEFORE" ] && [ -z "${3:-}" ] && SESSION="$SESSION"
  fi
  if ! tail -c 4000 "$OUT" | grep -q '"stopReason":"error"'; then
    cp "$OUT" "$LOG_DIR/$LABEL.final.jsonl"
    exit 0
  fi
  if ! tail -c 4000 "$OUT" | grep -qE 'rate_limit|overloaded|authentication_error|429|529|401'; then
    cp "$OUT" "$LOG_DIR/$LABEL.final.jsonl"
    exit 2
  fi
  MESSAGE="$CONTINUE_MESSAGE"
  sleep "$RETRY_WAIT_SECONDS"
done
exit 3
