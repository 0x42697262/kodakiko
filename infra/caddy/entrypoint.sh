#!/bin/sh
set -eu
set -a
. /etc/caddy/csp-hashes.env
set +a
exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
