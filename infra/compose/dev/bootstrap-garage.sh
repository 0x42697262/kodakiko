#!/bin/sh
set -eu

cd "$(dirname "$0")/../../.."

COMPOSE="docker compose -p kodakiko-dev -f infra/compose/docker-compose.dev.yml"
BUCKET="kodakiko-dev"
KEY_NAME="dev"
ENV_FILE="apps/server/.env.dev"
ENV_EXAMPLE="apps/server/.env.dev.example"
STATUS_ATTEMPTS=30

garage() {
  $COMPOSE exec -T garage-dev /garage "$@"
}

attempt=0
until status="$(garage status 2>/dev/null)"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge "$STATUS_ATTEMPTS" ]; then
    echo "Garage did not answer 'garage status' in time." >&2
    exit 1
  fi
  sleep 1
done

if printf '%s' "$status" | grep -q "NO ROLE ASSIGNED"; then
  node_id="$(printf '%s' "$status" | sed -n 's/^\([0-9a-f]\{16\}\)[[:space:]].*/\1/p' | head -n 1)"
  garage layout assign -z dc1 -c 1G "$node_id"
  garage layout apply --version 1
fi

garage bucket info "$BUCKET" >/dev/null 2>&1 || garage bucket create "$BUCKET"
garage key info "$KEY_NAME" >/dev/null 2>&1 || garage key create "$KEY_NAME"
garage bucket allow --read --write --owner "$BUCKET" --key "$KEY_NAME"

access_key_id="$(garage key info --key-id "$KEY_NAME" | tr -d '\r\n')"
secret_access_key="$(garage key info --secret-key "$KEY_NAME" | tr -d '\r\n')"

[ -f "$ENV_FILE" ] || cp "$ENV_EXAMPLE" "$ENV_FILE"
grep -v '^S3_ACCESS_KEY_ID=\|^S3_SECRET_ACCESS_KEY=' "$ENV_FILE" > "$ENV_FILE.tmp"
{
  cat "$ENV_FILE.tmp"
  echo "S3_ACCESS_KEY_ID=$access_key_id"
  echo "S3_SECRET_ACCESS_KEY=$secret_access_key"
} > "$ENV_FILE"
rm -f "$ENV_FILE.tmp"
chmod 600 "$ENV_FILE"
echo "Garage bucket $BUCKET is ready; the S3 credentials were written to $ENV_FILE."
