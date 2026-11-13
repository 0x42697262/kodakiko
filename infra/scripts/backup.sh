#!/bin/sh
set -eu

COMPOSE_DIR="${COMPOSE_DIR:-$(cd "$(dirname "$0")/../compose" && pwd)}"
DAILY_KEEP=14
WEEKLY_KEEP=8
WEEKLY_DAY=7
DAYS_PER_WEEK=7

cd "$COMPOSE_DIR"
set -a
. ./.env
set +a
S3_ACCESS_KEY_ID="$(sed -n 's/^S3_ACCESS_KEY_ID=//p' .env.api)"
S3_SECRET_ACCESS_KEY="$(sed -n 's/^S3_SECRET_ACCESS_KEY=//p' .env.api)"
S3_BUCKET="$(sed -n 's/^S3_BUCKET=//p' .env.api)"
: "${BACKUP_DIR:?BACKUP_DIR is not set in .env}"
: "${RCLONE_REMOTE:?RCLONE_REMOTE is not set in .env}"

prune() {
  directory="$1"
  today="$(date -u +%s)"
  for file in "$directory"/kodakiko-*.dump; do
    [ -e "$file" ] || continue
    day="$(basename "$file" .dump | sed 's/^kodakiko-//')"
    stamp="$(date -u -d "$day" +%s)"
    age_days=$(( (today - stamp) / 86400 ))
    weekday="$(date -u -d "$day" +%u)"
    if [ "$age_days" -lt "$DAILY_KEEP" ]; then continue; fi
    if [ "$weekday" -eq "$WEEKLY_DAY" ] && [ "$age_days" -lt $((WEEKLY_KEEP * DAYS_PER_WEEK)) ]; then continue; fi
    rm -f "$file"
  done
}

if [ "${1:-}" = "--prune-only" ]; then
  prune "$2"
  exit 0
fi

mkdir -p "$BACKUP_DIR/pg"
dump="$BACKUP_DIR/pg/kodakiko-$(date -u +%F).dump"
docker compose exec -T postgres pg_dump -U kodakiko -d kodakiko -Fc > "$dump.tmp"
mv "$dump.tmp" "$dump"
prune "$BACKUP_DIR/pg"

rclone() {
  docker compose --profile backup run --rm \
    -e RCLONE_CONFIG_GARAGE_TYPE=s3 \
    -e RCLONE_CONFIG_GARAGE_PROVIDER=Other \
    -e RCLONE_CONFIG_GARAGE_ENDPOINT=http://garage:3900 \
    -e RCLONE_CONFIG_GARAGE_REGION=garage \
    -e RCLONE_CONFIG_GARAGE_FORCE_PATH_STYLE=true \
    -e RCLONE_CONFIG_GARAGE_ACCESS_KEY_ID="$S3_ACCESS_KEY_ID" \
    -e RCLONE_CONFIG_GARAGE_SECRET_ACCESS_KEY="$S3_SECRET_ACCESS_KEY" \
    backup "$@"
}

rclone copy /backup/pg "$RCLONE_REMOTE/pg"
rclone sync "garage:$S3_BUCKET" "$RCLONE_REMOTE/bucket"
echo "Backup finished: $dump"
