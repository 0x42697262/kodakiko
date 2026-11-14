# Operations guide

How to run Kodakiko on a home server or a VPS behind a private VPN, with real HTTPS, backups and a
restore drill. Design background: `docs/design/deployment.md`.

All commands run in `infra/compose/` on the server unless a step says otherwise. In phase 1 (no
registry yet) every `docker compose` command also gets `-f docker-compose.yml -f
docker-compose.build.yml`, which builds the two images locally instead of pulling them.

## 1. Requirements

1. Docker Engine with the Compose plugin (`docker compose version` prints v2.x or newer).
2. A domain on a DNS provider with an API token that can edit DNS records. Cloudflare is the
   documented provider (D-68): create a token with "Zone › DNS › Edit" for that one zone.
3. A VPN (Tailscale, NetBird or Headscale) on the server and on every device that uses Kodakiko.
4. At least 2 GB RAM and enough disk for the journal plus about 10 GB for images and the database.

## 2. DNS

1. Create `A` (and `AAAA` if the VPN has IPv6) records for `kodakiko.<domain>` and
   `s3.kodakiko.<domain>`, both pointing to the server's **VPN** address (for Tailscale, the
   `100.x.y.z` address from `tailscale ip -4`).
2. Turn proxying off for both records (Cloudflare: grey cloud, "DNS only").
3. Expected: `dig +short kodakiko.<domain>` from any machine prints the VPN address. Certificates
   come through the DNS-01 challenge, so no public port is ever opened.

## 3. First run

1. Copy the repository (or a release archive) to `/opt/kodakiko`; `cd /opt/kodakiko/infra/compose`.
2. `cp .env.example .env` and `cp .env.api.example .env.api`; `chmod 600 .env .env.api`.
3. Fill `.env`:
   - `KODAKIKO_VERSION` (release version, or any label such as `local` in phase 1),
     `REGISTRY` (`ghcr.io/<owner>`; any value in phase 1),
   - `BIND_IP` = the VPN address from §2, `DOMAIN`, `ACME_EMAIL`, `DNS_API_TOKEN`,
   - `POSTGRES_PASSWORD`: `openssl rand -hex 24`,
   - `GARAGE_RPC_SECRET`: `openssl rand -hex 32` (Garage needs exactly 32 bytes in hex),
   - `GARAGE_ADMIN_TOKEN`: `openssl rand -hex 32`,
   - `BACKUP_DIR` and `RCLONE_REMOTE` (§5).
4. Fill `.env.api`:
   - `PUBLIC_BASE_URL=https://kodakiko.<domain>`,
     `S3_PUBLIC_ENDPOINT=https://s3.kodakiko.<domain>`,
   - `FAKE_PARAMS_SECRET`: `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='` (base64url, at
     least 32 bytes; the server refuses to start otherwise).
5. `docker compose up -d postgres garage`.
6. `docker compose --profile setup run --rm garage-setup` prints `S3_ACCESS_KEY_ID=…` and
   `S3_SECRET_ACCESS_KEY=…` **once**. Paste both lines into `.env.api`. Running it again is safe;
   it then prints only the key id.
7. `docker compose up -d`. Expected: `docker compose ps` shows `caddy`, `api`, `postgres` and
   `garage` running; `docker compose logs caddy` shows `certificate obtained successfully` for both
   names within about two minutes.
8. `docker compose exec api node dist/cli.js apply-bucket-cors` → prints the applied origin.
9. `docker compose exec api node dist/cli.js create-invite --role admin` → prints an invite URL.
   Open it on a device that is in the VPN (D-47) and create the first account.

## 4. Exposure

Compose publishes ports 80 and 443 on `BIND_IP` only. Docker-published ports are **not** filtered
by `ufw` or most host firewalls, so binding to the VPN address is what keeps the service private.
Never set `BIND_IP=0.0.0.0` in the private profile.

Check from a machine outside the VPN: `curl -m 5 https://kodakiko.<domain>/` must fail (timeout).
Inside the VPN it must answer with a valid certificate.

## 5. Backups

What is backed up: a Postgres dump (custom format) every night, kept 14 daily and 8 weekly
(Sundays), copied off the host; and a sync of the bucket (ciphertext only) to the same remote.

1. Configure the rclone remote once (any rclone backend: another disk, Backblaze B2, a friend's
   server over SFTP): `docker compose --profile backup run --rm backup config`. The config is kept
   in the `rclone-config` volume.
2. Set `RCLONE_REMOTE` in `.env` to `<remote name>:<path>`, for example `b2:kodakiko-backup`.
3. Run once by hand: `../scripts/backup.sh`. Expected last line: `Backup finished: …dump`.
4. Install the timer:
   `sudo cp ../systemd/kodakiko-backup.* /etc/systemd/system/`, adjust `COMPOSE_DIR` in the
   service if the repository is not in `/opt/kodakiko`, then
   `sudo systemctl daemon-reload && sudo systemctl enable --now kodakiko-backup.timer`.
5. Check: `systemctl list-timers kodakiko-backup.timer` shows the next run (03:30 local time).

## 6. Restore drill

Do this on a second machine (or VM) at least every 90 days (release checklist item 8).

1. On the original server: `docker compose exec api node dist/cli.js stats` and keep the output.
2. On the new machine, do §3 steps 1-6 with the **same** `.env` and `.env.api` (same secrets).
3. Copy the newest dump back:
   `docker compose --profile backup run --rm backup copy "$RCLONE_REMOTE/pg/kodakiko-<date>.dump" /backup/pg/`.
4. Restore it: `docker compose exec -T postgres pg_restore -U kodakiko -d kodakiko --clean
   --if-exists < "$BACKUP_DIR/pg/kodakiko-<date>.dump"`.
5. Copy the bucket back. The helper in `backup.sh` passes the Garage credentials; for a manual
   run use the same `-e RCLONE_CONFIG_GARAGE_*` options as the script, with
   `sync "$RCLONE_REMOTE/bucket" garage:kodakiko`.
6. `docker compose up -d`; sign in on a device; the gallery loads and an original opens.
7. `docker compose exec api node dist/cli.js stats` matches step 1 (users, entries, bytes).

## 7. Updates and rollback

1. Read the release notes (migrations are expand-only unless the notes say otherwise).
2. Set `KODAKIKO_VERSION` in `.env`; `docker compose pull && docker compose up -d`. The API runs
   pending migrations once before it listens.
3. Rollback: set the previous `KODAKIKO_VERSION` and run step 2 again.
4. Phase 1 (local builds): `git pull`, then
   `docker compose -f docker-compose.yml -f docker-compose.build.yml up -d --build`.

## 8. Going public

Configuration only (D-41, `deployment.md` §6). Two options:

1. **Full public access:** a Cloudflare Tunnel container on the compose network (or a public
   `BIND_IP` on a VPS). With a tunnel, add `trusted_proxies static private_ranges` to the global
   block of the Caddyfile, so Caddy keeps the client address that cloudflared forwards. The API
   still trusts forwarded headers from private peers only (`TRUSTED_PROXIES`).
2. **Only public links for viewers outside the VPN:** a tunnel or Tailscale Funnel limited to the
   paths `/s/*`, `/_expo/static/*`, `/media-sw.js`, `/api/v1/config` and
   `/api/v1/public-links/*`, and `PUBLIC_LINK_PROXY=true` in `.env.api`, because the `s3.` host
   stays private.

Before either: review the limits in `security.md` §4 and keep `SIGNUP_MODE=invite`.

## 9. Rotating secrets

| Secret | How | Effect |
|---|---|---|
| `FAKE_PARAMS_SECRET` | new value in `.env.api` (§3 step 4 command), `docker compose up -d api` | safe any time; fake sign-in answers change and active public-link viewing sessions end |
| S3 key | `docker compose exec garage /garage key create kodakiko-api-2`, `… /garage bucket allow --read --write --owner kodakiko --key kodakiko-api-2`, put the new id and secret in `.env.api`, `docker compose up -d api`, then `… /garage key delete --yes <old key id>` | uploads in flight retry |
| DNS token | create a new token at the provider, update `.env`, `docker compose up -d caddy`, revoke the old token | none |
| Postgres password | `docker compose exec postgres psql -U kodakiko -c "ALTER USER kodakiko PASSWORD '<new>'"`, update `.env`, `docker compose up -d api` | short API restart |

## 10. Logs

`docker compose logs -f api` prints JSON lines. Useful event names: `auth.*` (sign-in, sessions),
`object.*` (uploads, collection), `job.*` (background jobs and their durations), `link.*` (public
links), `admin.*` (admin actions). Caddy: `docker compose logs caddy` (certificates, proxy errors).
