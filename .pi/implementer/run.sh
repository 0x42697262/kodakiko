#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
MODEL="${IMPLEMENTER_MODEL:-anthropic/claude-sonnet-5-5}"
LABEL="$1"
MESSAGE_FILE="$2"
RESUME="${3:-}"
LOG_DIR="$ROOT/.pi/implementer/logs"
mkdir -p "$LOG_DIR" "$ROOT/.pi/implementer/sessions"
ARGS=(
  -p --mode json
  --model "$MODEL" --thinking high
  -ne -e "$ROOT/.pi/extensions/implementer-guard.ts"
  --no-mcp -ns -np
  --tools read,bash,edit,write,grep,find,ls
  --append-system-prompt "$ROOT/.pi/implementer/prompt.md"
  --session-dir "$ROOT/.pi/implementer/sessions"
)
if [ -n "$RESUME" ]; then ARGS+=(--session "$RESUME"); fi
exec pi-bolt "${ARGS[@]}" "$(cat "$MESSAGE_FILE")" > "$LOG_DIR/$LABEL.jsonl" 2> "$LOG_DIR/$LABEL.err"
