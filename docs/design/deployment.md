# Deployment

Status: draft 1 (2026-11-11). Normative for `infra/compose/`. Covers the two profiles (D-41), the
compose stack, HTTPS via DNS-01 (D-40), VPN access (D-36), backups (D-28/D-34), and the
going-public checklist.

## 1. Profiles

One codebase, one compose stack, two profiles that differ only in ingress and config:

| Profile | Reachability | Use |
|---|---|---|
| `private` (default, now) | LAN + mesh VPN only (Tailscale/NetBird/Headscale, D-36). No public ingress | The programmer's own server |
| `public` (later) | Internet-reachable through an ingress (Cloudflare Tunnel, VPS reverse proxy, or port forward) | Opening to more users (D-41) |

`KODAKIKO_PROFILE` is documentation-only: nothing in the app reads it. What actually differs is
which ingress container is enabled and the values of the hardening settings (section 6).

## 2. Stack (`infra/compose/docker-compose.yml`)

| Service | Image | Purpose |
|---|---|---|
| `caddy` | `caddy:2` + custom build with the DNS provider plugin (xcaddy; the compose file builds it from a small Dockerfile) | TLS via DNS-01 (D-40), reverse proxy for `api` and `garage` |
| `api` | built from `apps/server` (pinned Node 22 base, non-root) | The Fastify server |
| `postgres` | `postgres:17` (pinned digest) | Server rows (`data-model.md`) |
| `garage` | `dxflrs/garage` pinned version (F-S3-02) | S3-compatible object store, single node, data on a host bind mount |

Networks: one internal compose network; only `caddy` publishes ports (`80`, `443` — port 80 only
redirects and answers ACME HTTP challenges if ever needed; DNS-01 does not require it). `api`,
`postgres` and `garage` are unreachable except through Caddy or the compose network.

Host names (all under the programmer's domain, D-40):

| Host | Serves |
|---|---|
| `kodakiko.<domain>` | web app at `/`, API at `/api/*`, health at `/health/*` (one origin, so the web app needs no API CORS; `client.md` §7.1) |
| `s3.kodakiko.<domain>` | Garage S3 endpoint (presigned URLs point here; `storage.md` §1) |

Caddy routes `/api/*` and `/health/*` on the app host to `api` (request body limit
`API_MAX_BODY_BYTES`, 2 MiB: JSON only, media never passes through the API), everything else on
the app host to the static web build, and the `s3.` host to Garage with `request_body max_size`
= `MULTIPART_PART_BYTES` + 1 MiB.

### 2.1 Bucket CORS

Browsers upload and download straight to `s3.` from the app origin, so the bucket gets one CORS
rule (applied by the `garage-setup` service with `PutBucketCors`, which Garage implements,
F-S3-05; B2 and R2 take the same rule through their consoles or S3 API):

| Field | Value |
|---|---|
| AllowedOrigins | `https://kodakiko.<domain>` (exactly one origin, no wildcard) |
| AllowedMethods | `GET`, `PUT`, `HEAD` |
| AllowedHeaders | `Range`, `Content-Type` |
| ExposeHeaders | `Content-Range`, `Content-Length`, `Accept-Ranges` |
| MaxAgeSeconds | 3600 |

The Android app is not a browser and is not subject to CORS.

### 2.2 DNS-01 certificates (D-40)

Caddy obtains Let's Encrypt certificates using the DNS-01 challenge through the domain's DNS
provider API (e.g. `caddy-dns/cloudflare`), so no inbound port is required and it works behind
CGNAT and inside a VPN-only network. The DNS API token is a Caddy env var from `.env`
(never in the repo; `.env.example` lists every key, guidelines 9.1).

### 2.3 VPN access (D-36)

Devices join the mesh VPN (Tailscale, NetBird, or a self-hosted Headscale/NetBird coordination
server — the app does not care which). The domain's DNS A/AAAA records point at the server's VPN
IP (or the host runs split DNS so the records resolve to the VPN IP only inside the tailnet).
Result: phones reach `kodakiko.<domain>` and `s3.kodakiko.<domain>` only when the VPN is up; otherwise the app is offline-capable
and syncs on reconnect (`sync.md` §8).

The compose stack does not run the VPN client; that is host-level setup documented in the README.
The server treats VPN peers as untrusted clients anyway (auth, rate limits, TLS all apply).

## 3. Configuration

All server settings come from environment variables validated at boot
(`apps/server/src/config/settings.ts`, guidelines 4.3). `.env.example` documents every variable.
Groups:

The complete list with types, defaults and settings paths is in the specs that introduce each
group (03 §2.3, 04 §3.1, 05 §2, 06 §2, 17 §1.1, 18 §1.1); `infra/compose/.env.example` (production)
lists every variable, plus the compose-only ones of spec 21 §3 (`REGISTRY`, `KODAKIKO_VERSION`,
`BIND_IP`, `DOMAIN`, `ACME_EMAIL`, `DNS_API_TOKEN`, `S3_MAX_BODY`, `POSTGRES_PASSWORD`,
`GARAGE_RPC_SECRET`, `GARAGE_ADMIN_TOKEN`, `GARAGE_DATA_PATH`, `BACKUP_DIR`, `RCLONE_REMOTE`).
Summary:

| Group | Variables |
|---|---|
| `server` | `PORT`, `HOST`, `PUBLIC_BASE_URL`, `TRUST_PROXY_HOPS`, `REQUIRE_HTTPS`, `SERVER_VERSION`, `SHUTDOWN_TIMEOUT_MS` |
| `database` | `DATABASE_URL` (required), `DATABASE_POOL_MAX`, `DATABASE_STATEMENT_TIMEOUT_MS`, `DATABASE_CONNECT_TIMEOUT_MS` |
| `flags` | `FLAG_SHARING`, `FLAG_PUBLIC_LINKS`, `SIGNUP_MODE` (`invite`/`open`) |
| `limits` | `MAX_MEDIA_PER_ENTRY`, `MAX_UPLOAD_BYTES`, `DEFAULT_QUOTA_BYTES`, `MAX_ENTRY_METADATA_BYTES`, `MAX_COLLECTION_METADATA_BYTES` |
| `auth` | `FAKE_PARAMS_SECRET` (required; fake salts and recovery fakes, `crypto.md` §5.2, §5.5), `SESSION_IDLE_DAYS`, `MAX_FAILED_SIGN_INS`, `FAILED_SIGN_IN_WINDOW_MINUTES`, `INVITE_DEFAULT_EXPIRY_DAYS` |
| `accounts` | `ACCOUNT_DELETION_GRACE_DAYS` |
| `storage` | `STORAGE_PROVIDER`, `S3_ENDPOINT` (server → bucket), `S3_PUBLIC_ENDPOINT` (host inside presigned URLs), `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`, `S3_FORCE_PATH_STYLE`, `STORAGE_REQUEST_TIMEOUT_MS`, `SINGLE_PART_MAX_BYTES`, `MULTIPART_PART_BYTES`, `UPLOAD_URL_TTL_SECONDS`, `DOWNLOAD_URL_TTL_SECONDS`, `PENDING_UPLOAD_HOURS`, `OBJECT_GRACE_DAYS`, `MAX_PRESIGNS_PER_HOUR` |
| `entries` / `sync` | `TRASH_DAYS`, `TOMBSTONE_RETENTION_DAYS`, `MAX_DIFF_PAGE_SIZE` |
| `jobs` | `JOBS_ENABLED`, `JOB_*_INTERVAL_MINUTES` (one per job) |
| `log` | `LOG_LEVEL` |
| sharing (spec 17) | `MAX_RECIPIENTS_PER_COLLECTION`, `MAX_SHARED_COLLECTIONS_PER_USER` |
| public links (spec 18) | `PUBLIC_LINK_PROXY`, `MAX_PUBLIC_LINKS_PER_USER`, `MAX_ENTRIES_PER_LINK`, `LINK_VIEW_TOKEN_MINUTES` |

The clients need no build-time server config: the web app uses its own origin and the Android app
asks for the server at first run (`client.md` §7.1). Both read `GET /api/v1/config`
(`api.md` §11) for flags and limits.

## 4. First-run and admin bootstrap (D-27, D-47)

1. `docker compose up -d postgres garage`, wait for healthy.
2. One-shot: `docker compose run --rm api node dist/cli.js create-invite --role admin` prints
   `https://kodakiko.<domain>/invite#kdk_i_…`.
3. The admin opens that URL in the app/web, signs up (keys are generated client-side), and the
   invite is consumed.
4. `docker compose up -d` the rest.
5. The admin creates invites for family/friends from `/settings` → admin (or the CLI).

Garage setup (bucket creation, access key) is a one-shot `garage` CLI step in the README; the
compose file includes a `garage-setup` profile service that runs it idempotently.

## 5. Backups (D-28, D-34)

Everything on the server is ciphertext or derived, so backups need no key custody:

| Data | Method | Schedule |
|---|---|---|
| Postgres | `pg_dump` custom format to the backup volume, then `rclone copy` off-box | nightly; keep 14 daily, 8 weekly |
| Bucket | `rclone sync garage-bucket backup-bucket` (or `--backup-dir` for versioned retention) | nightly; the copy is the "versioning" Garage lacks (F-S3-02) |
| `.env` | manual, in the operator's password manager | on change |

Restore drill documented in the README: restore Postgres, point `S3_*` at the backup bucket (or
restore it), start the stack, sign in, verify a gallery loads. A reconciliation job
(`reconcile-usage`, `data-model.md` §5) heals `used_bytes` after a partial restore.

Client-side: the phone's ciphertext cache is derived; a new install re-downloads on demand. The
recovery key is the user's own backup of access (`crypto.md` §5.6) — the app nags once until the
user confirms it is written down.

## 6. Going public (D-41 checklist)

Config and ingress only; no code change:

1. Add the ingress (Cloudflare Tunnel container / VPS proxy / port forward) so `kodakiko.<domain>` and `s3.kodakiko.<domain>`
   resolve publicly; keep DNS-01 certificates.
2. Review `security.md` §4 limits; lower them if abuse appears.
3. Keep `SIGNUP_MODE=invite` until deliberately switched to `open`; before switching, read the
   moderation note in `planning/context.md` ("Public deployment readiness").
4. Public links for viewers outside the VPN (D-36) need a public path to the viewer page and the
   viewer API only, plus `PUBLIC_LINK_PROXY=true` so photo ciphertext is streamed through the API
   (`/api/v1/public-links/*/objects/*/content`) instead of presigned URLs to the private `s3.` host
   (`storage.md` §8; operations guide §8). The setting is introduced by spec 18.
5. Enable error tracking (Sentry or similar, guidelines 7.3) with secret redaction verified.

## 7. Updates

1. Images are pinned by digest; Renovate opens update PRs (`minimumReleaseAge`, D-11/F-TOOL-06).
2. Set `KODAKIKO_VERSION` in `.env`, then `docker compose pull && docker compose up -d` is the deploy
   (images from the release workflow, spec 21 §6; the web app is inside the Caddy image); Postgres migrations run in the
   `api` entrypoint before listening (Kysely migrator, `data-model.md` §1), so a new `api` image
   migrates once, idempotently.
3. Rollback: previous image tag; migrations must be expand-and-contract so an older `api` still
   runs against a migrated DB (guidelines 4.4).
4. Client releases: Android via sideloaded APK / Play internal track later; web is part of the
   compose stack (`web` image), so it ships with the server.

## 8. Flags registry (`docs/flags.md` content, lives here until the file is split out)

| Flag | Default | Owner | Removal target |
|---|---|---|---|
| `sharing` | off | programmer | when stable for 1 month of daily use |
| `public_links` | off | programmer | when stable for 1 month of daily use |
| `signupMode` | `invite` | programmer | permanent config, not a temporary flag |
| `PUBLIC_LINK_PROXY` | false | programmer | permanent configuration (not a temporary flag): `true` whenever link viewers cannot reach the `s3.` host |
