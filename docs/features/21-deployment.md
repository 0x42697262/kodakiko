# 21 — Production deployment, CI, release

Status: draft 1 (2026-11-11). Phases (D-73): §1-§4 and §7a belong to phase 1 (local); §5-§7 (CI,
release workflow, registry, release checklist, Renovate) to phase 2 (online). In phase 1 the
compose file is used with `docker-compose.build.yml` (local builds, no registry) and `BIND_IP` is the
machine's LAN or VPN address.

Goal: one `docker compose` stack that runs Kodakiko on a home server or VPS behind the private VPN
(D-36) with real HTTPS via DNS-01 (D-40), ready to go public by configuration only (D-41); backups
and a restore drill; CI that enforces every gate of D-11; a release workflow that produces the
server images, the web build, a signed Android APK, an SBOM and the license list; and a release
checklist.

Read first: `docs/design/deployment.md` (all), `docs/design/security.md` §§3-5,
`docs/dependencies.md` (CI gates, container images), specs 03 (server settings, CLI), 05
(`apply-bucket-cors`, Garage bootstrap facts F-S3-06), 07a (`build:web`, workers), 07b (dev stack,
F-S3-07), 15 (`media-sw.js`), 19 (CLI); decisions D-36, D-40, D-41, D-66, D-68, D-69. Depends on
all earlier specs.

## 1. Images (`infra/docker/`)

### 1.1 `api.Dockerfile`

- Stage `build` from the pinned `node:22-bookworm-slim` digest: `corepack enable`; copy the
  workspace manifests first (`package.json`, `pnpm-lock.yaml`, `pnpm-workspace.yaml`, every
  package's `package.json`) for layer caching; `pnpm install --frozen-lockfile --filter
  @kodakiko/server...`; copy sources; `pnpm --filter @kodakiko/server build` (spec 03 esbuild bundle).
- Stage `runtime` from the same base: copy only `apps/server/dist/`; `USER node`; `WORKDIR /app`;
  `ENV NODE_ENV=production`; `EXPOSE 8080`; `HEALTHCHECK` = `node -e "fetch('http://127.0.0.1:8080/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"`
  every 30 s; `CMD ["node", "dist/main.js"]`. The bundle needs no `node_modules` (spec 03 bundles
  every dependency; `pg-native` is external and intentionally absent).

### 1.2 `caddy.Dockerfile` (web build + Caddy with a DNS module, D-68)

- Stage `web`: same Node base; install with `--filter @kodakiko/client...`; `pnpm --filter
  @kodakiko/client build:web` (07a: builds the workers and `media-sw.js`, then `expo export
  --platform web`); then `node infra/scripts/csp-hashes.ts apps/client/dist` (§2.3) → writes
  `apps/client/dist/csp-hashes.env`.
- Stage `caddybuild`: pinned `caddy:2-builder` digest; `xcaddy build ${CADDY_VERSION} --with
  ${DNS_MODULE}` with build args `CADDY_VERSION=v2.11.8` and
  `DNS_MODULE=github.com/caddy-dns/cloudflare@v0.2.4` (commit `a8737d09`; both recorded in
  `docs/dependencies.md` §Container images by this spec; v2.11.8 is the newest release older than
  the 7-day rule on 2026-11-11).
- Stage `runtime`: pinned `caddy:2` digest; copy `/usr/bin/caddy` from `caddybuild`; copy the web
  build to `/srv/web` **excluding** `csp-hashes.env`, which goes to `/etc/caddy/csp-hashes.env` (not
  served); copy `infra/caddy/Caddyfile` to `/etc/caddy/Caddyfile` and `infra/caddy/snippets/` to
  `/etc/caddy/snippets/` (imports are relative to the Caddyfile); copy `infra/caddy/entrypoint.sh` to
  `/usr/local/bin/entrypoint.sh`; `ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]`.
- The web stage also runs `node scripts/check-licenses.ts --write` before `build:web`, so the
  bundled `assets/licenses.json` is real in production (spec 20 §6).

### 1.3 Other images

`postgres:17` and `dxflrs/garage:v2.5.0` by digest (already recorded); `rclone/rclone` pinned digest
for backups (add to the allowlist's container images in this spec).

## 2. Caddy (`infra/caddy/`)

### 2.1 `Caddyfile`

```
{
  email {$ACME_EMAIL}
}

(tls_dns) {
  tls {
    dns cloudflare {env.DNS_API_TOKEN}
  }
}

kodakiko.{$DOMAIN} {
  import tls_dns
  encode zstd gzip
  handle /api/* {
    request_body {
      max_size 2MiB
    }
    reverse_proxy api:8080
  }
  handle /health/* {
    reverse_proxy api:8080
  }
  handle /media-sw.js {
    import snippets/security-headers.caddy
    header Cache-Control "no-cache"
    root * /srv/web
    file_server
  }
  handle /_expo/static/* {
    import snippets/security-headers.caddy
    header Cache-Control "public, max-age=31536000, immutable"
    root * /srv/web
    file_server
  }
  handle {
    import snippets/security-headers.caddy
    header Cache-Control "no-cache"
    root * /srv/web
    try_files {path} /index.html
    file_server
  }
}

s3.kodakiko.{$DOMAIN} {
  import tls_dns
  request_body {
    max_size {$S3_MAX_BODY}
  }
  reverse_proxy garage:3900 {
    header_up Host {host}
  }
}
```

- Blocks such as `request_body` must open on their own line (verified with `caddy adapt` v2.11.9:
  `request_body { max_size 2MB }` on one line is a parse error). Sizes use IEC units: `2MiB`
  equals `API_MAX_BODY_BYTES` (2,097,152); `S3_MAX_BODY` default `9MiB` = `max(SINGLE_PART_MAX_BYTES,
  MULTIPART_PART_BYTES) + 1 MiB` (9,437,184). `MB` would be decimal and too small.
- The `dns cloudflare …` line is the only provider-specific line (D-68); other providers: change the
  module and its arguments per the module's README.
- `header_up Host {host}` keeps the original `Host` for SigV4 presigned URLs (storage.md §1, F-S3-07).
- No `trusted_proxies` in the private profile: Caddy sees real client addresses and overwrites
  `X-Forwarded-For`; the API trusts forwarded headers only from peers in `TRUSTED_PROXIES`
  (default `loopback,uniquelocal`, which covers Caddy on the compose network; D-77). In the public
  profile with Cloudflare Tunnel the operator adds `trusted_proxies static private_ranges` to Caddy's
  global block (cloudflared runs on the same private network), so Caddy keeps the client address
  that cloudflared forwards, and the API still trusts only private peers (operations guide §8);
  clients cannot spoof `X-Forwarded-For` to evade per-IP limits.

### 2.2 `snippets/security-headers.caddy`

```
header {
  Strict-Transport-Security "max-age=63072000; includeSubDomains; preload"
  X-Content-Type-Options "nosniff"
  Referrer-Policy "no-referrer"
  Permissions-Policy "camera=(self), microphone=(self), geolocation=(self)"
  Cross-Origin-Resource-Policy "same-origin"
  Content-Security-Policy "default-src 'self'; script-src 'self' 'wasm-unsafe-eval' {$CSP_SCRIPT_HASHES}; worker-src 'self'; connect-src 'self' {$S3_PUBLIC_ORIGIN}; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'"
  -Server
}
```

The file is created by spec 07c (its UI test stack serves the web app with it, so the CSP is tested
from the start); `S3_PUBLIC_ORIGIN` is set in compose to `https://s3.kodakiko.${DOMAIN}` (production)
or `http://localhost:9581` (UI tests). The snippet is imported only inside the web `handle` blocks (§2.1), never for `/api/*` or
`/health/*`, so API responses keep the stricter headers set by helmet (spec 03).

### 2.3 CSP hashes (`infra/scripts/csp-hashes.ts`)

Node script (no new dependency): reads every `.html` file of the export, extracts the content of each
`<script>` element **without** a `src` attribute, computes `'sha256-<base64>'` of each, and writes
`CSP_SCRIPT_HASHES="'sha256-a' 'sha256-b'"` (the whole value in **double quotes**, so the shell can
source it; verified that an unquoted value with spaces breaks) into `dist/csp-hashes.env`. Expo's
`single` web output currently has **no** inline scripts (verified: `@expo/cli` 58.1.19 emits
`<script src="/_expo/static/js/web/…" defer>` only), so the value is normally `""`; the script stays
so a future inline script cannot silently break the page. `infra/caddy/entrypoint.sh`: `set -a; .
/etc/caddy/csp-hashes.env; set +a; exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile`.
Caddy substitutes `{$CSP_SCRIPT_HASHES}` when parsing the config, also inside quoted header values
(verified). Unit test with a fixture HTML (two inline scripts, one external) → exact output line.

## 3. Compose (`infra/compose/docker-compose.yml`)

| Service | Notes |
|---|---|
| `caddy` | `image: ${REGISTRY}/kodakiko-caddy:${KODAKIKO_VERSION}` (pushed by the release workflow, §6); ports `${BIND_IP}:80:80` and `${BIND_IP}:443:443` — Docker-published ports bypass host firewalls such as `ufw`, so the private profile binds them to the server's **VPN address** (`BIND_IP`), never `0.0.0.0` (D-36); volumes `caddy-data`, `caddy-config`; env `DOMAIN`, `ACME_EMAIL`, `DNS_API_TOKEN`, `S3_MAX_BODY`, `S3_PUBLIC_ORIGIN=https://s3.kodakiko.${DOMAIN}`; `restart: unless-stopped` |
| `api` | `image: ${REGISTRY}/kodakiko-api:${KODAKIKO_VERSION}`; `env_file: .env`; no published ports; `read_only: true` with `tmpfs: /tmp`; `depends_on` postgres (healthy) and garage (started); `restart: unless-stopped` |
| `postgres` | pinned image; volume `pg-data`; env `POSTGRES_PASSWORD`, `POSTGRES_DB=kodakiko`, `POSTGRES_USER=kodakiko`; healthcheck `pg_isready`; no published ports |
| `garage` | pinned image; `infra/garage/garage.toml` (replication 1, `metadata_dir`/`data_dir` on volumes `garage-meta`/`garage-data` or host bind mounts set by `GARAGE_DATA_PATH`); `rpc_secret` and `admin_token` from env; no published ports |
| `garage-setup` | profile `setup`; runs `infra/garage/bootstrap.sh` (the F-S3-06 commands: layout, bucket `kodakiko`, key `kodakiko-api`, permissions; idempotent; prints the key id/secret once for `.env`) |

`infra/compose/docker-compose.build.yml` (override for building locally instead of pulling): adds
`build:` with the two Dockerfiles to `caddy` and `api`. Network: one internal bridge network; only
`caddy` publishes ports.

`infra/compose/.env.example` is the **production** superset: every server variable of the settings
tables in specs 03, 04, 05, 06, 17 and 18 with production defaults (`REQUIRE_HTTPS=true`,
`TRUSTED_PROXIES=loopback,uniquelocal`, `PUBLIC_BASE_URL=https://kodakiko.<domain>`, `S3_ENDPOINT=http://garage:3900`,
`S3_PUBLIC_ENDPOINT=https://s3.kodakiko.<domain>`, `STORAGE_PROVIDER=garage`, flags off,
`SIGNUP_MODE=invite`), plus the compose-only variables `REGISTRY`, `KODAKIKO_VERSION`, `BIND_IP`,
`DOMAIN`, `ACME_EMAIL`, `DNS_API_TOKEN`, `S3_MAX_BODY=9MiB`, `POSTGRES_PASSWORD`,
`GARAGE_RPC_SECRET`, `GARAGE_ADMIN_TOKEN`, `GARAGE_DATA_PATH`, `BACKUP_DIR`, `RCLONE_REMOTE`.
`apps/server/.env.example` stays the development/test file (spec 03). Secrets have empty values;
generation commands are in `docs/operations.md`.

## 4. Operations guide (`docs/operations.md`, new; linked from `docs/README.md` and the root README)

Sections (each a numbered procedure with expected output):
1. Requirements: Docker + Compose plugin, a domain on a DNS provider with an API token (Cloudflare
   documented, D-68), a VPN (Tailscale/NetBird/Headscale) on the server and every device.
2. DNS: A/AAAA records `kodakiko.<domain>` and `s3.kodakiko.<domain>` → the server's **VPN** IP
   (private profile); the DNS-01 challenge works without any public port.
3. First run: copy `.env.example`; generate secrets (`openssl rand -base64 32` /
   `openssl rand -hex 32` for Garage's rpc secret); `docker compose up -d postgres garage`;
   `docker compose --profile setup run --rm garage-setup` → paste the printed S3 key into `.env`;
   `docker compose up -d`; `docker compose exec api node dist/cli.js apply-bucket-cors`;
   `docker compose exec api node dist/cli.js create-invite --role admin` → open the URL on a device
   joined to the VPN (D-47).
4. Exposure: compose binds 80/443 to `BIND_IP` (the VPN address). Note for operators: Docker's
   published ports are not filtered by `ufw`; binding to the VPN address is what keeps them private.
5. Backups (deployment.md §5): `infra/scripts/backup.sh` (pg_dump custom format into
   `BACKUP_DIR/pg/kodakiko-<date>.dump`, keep 14 daily and 8 weekly; then `rclone copy
   BACKUP_DIR/pg ${RCLONE_REMOTE}/pg` so dumps leave the host, and `rclone sync` of the bucket to
   `${RCLONE_REMOTE}/bucket`; the remote is configured once with `rclone config` in the `rclone/rclone`
   container), run nightly by the provided systemd unit + timer files
   `infra/systemd/kodakiko-backup.{service,timer}`.
6. Restore drill: new empty stack → `pg_restore` → `rclone sync` back → start → sign in → gallery
   loads → `reconcile-usage` runs (or `docker compose exec api node dist/cli.js stats`).
7. Updates and rollback (deployment.md §7).
8. Going public (D-41 checklist, deployment.md §6): either full public access (Cloudflare Tunnel
   or a public `BIND_IP`; then `trusted_proxies static private_ranges` in Caddy for a tunnel, §2.1), or only
   public links for non-VPN viewers (D-36): a tunnel or Tailscale Funnel limited to the paths `/s/*`,
   `/_expo/static/*`, `/media-sw.js`, `/api/v1/config` and `/api/v1/public-links/*`, with
   `PUBLIC_LINK_PROXY=true` because the S3 host stays private.
9. Rotating secrets: `FAKE_PARAMS_SECRET` (safe any time; fake answers change and active
   public-link viewing sessions end), S3 key (create
   a new Garage key, update `.env`, restart `api`, delete the old key), DNS token, Postgres password.
10. Logs: `docker compose logs api` (JSON); the event names to look for (`auth.*`, `object.*`,
    `job.*`, `link.*`, `admin.*`).

## 5. CI (`.github/workflows/`)

The repository host is assumed to be GitHub (UNVERIFIED: if the programmer uses another host, stop
and ask before writing workflows). Every job starts with the same steps: checkout (pinned
`actions/checkout`), `corepack enable`, `actions/setup-node` with Node 22 and pnpm cache,
`pnpm install --frozen-lockfile`. All third-party actions are pinned by commit SHA, recorded in
`docs/dependencies.md` §CI actions (new section, this spec).

`ci.yml` (on push and pull request), jobs:

| Job | Steps after the common ones |
|---|---|
| `verify` | `pnpm verify` |
| `integration` | `docker compose -f infra/compose/docker-compose.test.yml up -d --wait`; Garage bootstrap (spec 05 §9); `pnpm --filter @kodakiko/server test:integration`; `pnpm --filter @kodakiko/e2e test` |
| `supply-chain` | `pnpm audit --audit-level moderate`; OSV-Scanner as a step: `google/osv-scanner-action/osv-scanner-action@<sha of v2.7.1>` with `scan-args: --lockfile=pnpm-lock.yaml`, reviewed exceptions in `osv-scanner.toml` at the repository root (each with a reason and an expiry date); `node scripts/check-licenses.ts` |
| `config` | `pnpm --filter @kodakiko/server exec node scripts/check-config.ts infra/compose/.env.example` (the script lives in `apps/server/scripts/`, so it resolves server code; it loads the file with dummy secrets through `loadSettings`, checks `S3_MAX_BODY` (IEC parsing: `KiB`/`MiB`/`GiB`) ≥ `max(SINGLE_PART_MAX_BYTES, MULTIPART_PART_BYTES) + 1 MiB`, and checks that every key of `SETTINGS_ENV_KEYS` — exported by `apps/server/src/config/settings.ts`, the list of variables its schema reads — appears in the file; unit-tested with a good and a bad fixture `.env`) |
| `images` | `docker build` of both Dockerfiles (no push) |

`scripts/check-licenses.ts` (repository root): runs `pnpm licenses list --json --prod --recursive`
(without `--recursive` pnpm 12 lists only the root's dependencies, which are dev-only, and the gate
would pass vacuously; verified in pnpm's documentation), whose output maps each license expression
to packages with `versions`. A license expression passes when it is in D-70's list, or is an SPDX
`OR` expression with at least one allowed alternative, or an `AND` expression whose parts are all
allowed (parentheses supported; anything else, including `UNLICENSED` and unknown strings, fails).
Prints offenders and exits 1. `--write` also writes `apps/client/assets/licenses.json` (`{ name,
version, license }[]`). Unit tests with fixture JSON for each rule.

## 6. Release (`.github/workflows/release.yml`, on tags `v*`)

1. Everything in `ci.yml`.
2. `node scripts/check-licenses.ts --write`; `pnpm sbom --sbom-format cyclonedx --prod --recursive
   --out sbom.cdx.json` (`--sbom-format` is required).
3. Build and push both images to the registry configured by the programmer (`REGISTRY` secret;
   GitHub Container Registry as default), tagged with the version and by digest; the digests go
   into the release notes.
4. Android: `pnpm --filter @kodakiko/client prebuild:android`; then in `apps/client/android`
   `./gradlew assembleRelease -Pandroid.injected.signing.store.file=$KEYSTORE_PATH
   -Pandroid.injected.signing.store.password=$ANDROID_KEYSTORE_PASSWORD
   -Pandroid.injected.signing.key.alias=$ANDROID_KEY_ALIAS
   -Pandroid.injected.signing.key.password=$ANDROID_KEY_PASSWORD` (the Expo template would otherwise
   sign release builds with the debug key); `KEYSTORE_PATH` is a temp file decoded from
   `ANDROID_KEYSTORE_BASE64` and deleted after the step; the keystore is created once by the
   programmer with `keytool` and kept outside the repository; artifact `kodakiko-<version>.apk` plus
   its SHA-256.
5. Attach the APK, its checksum, `sbom.cdx.json` and `licenses.json` to the release.

## 7. Release checklist (`docs/release-checklist.md`, new)

Each item: what to check and the expected result.
1. `VideoLauncher.canPlay()` is `true` (spec 13 placeholder replaced).
2. Diagnostics routes unreachable in the release APK and the production web build (`__DEV__` false):
   `/settings/diagnostics` redirects to `/`.
3. Merged release manifest: `allowBackup="false"`, data extraction rules present,
   `usesCleartextTraffic="false"`, `networkSecurityConfig` with `debug-overrides` only, permissions
   exactly `CAMERA`, `RECORD_AUDIO`, location (coarse/fine), `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `INTERNET` (and those added by Expo
   modules, listed in the report).
4. Web: production build loaded in Chrome with DevTools → no CSP violations; crypto vector check
   (diagnostics build) passes against the same build flags.
5. Flags in `.env`: `FLAG_SHARING`, `FLAG_PUBLIC_LINKS` as intended; `SIGNUP_MODE=invite`.
6. `curl -I https://kodakiko.<domain>/` shows the §2.2 headers; `curl -I …/api/v1/config` shows
   `default-src 'none'`.
7. Migrations of this release are expand-only (guidelines 4.4) or the release notes say otherwise.
8. A restore drill was done within the last 90 days (operations §6).
9. `grep -rn -E "UNVERIFIED|UNCONFIRMED" docs` → each hit is verified (and edited) or listed
   in the release notes as accepted, with a reason.
11. Security drills (security.md §7 items 3 and 4): a `pg_dump` of production grepped for a known
    tag, note word and username-free plaintext → zero hits; one bucket object downloaded with
    `rclone cat … | head -c 4` → `KDKS`.
12. Storage provider: if `STORAGE_PROVIDER` is not `garage`, the provider conformance suite
    passed against that provider (`pnpm --filter @kodakiko/server test:provider` with
    `PROVIDER_TEST_*` variables: single PUT, presigned multipart parts incl. UploadPart
    (F-S3-03), `ListParts`, `CompleteMultipartUpload`, Range GET, CORS rule; spec 05 adds this suite
    next to its integration tests).
10. SBOM, licenses and APK checksum attached.

## 7a. Repository extras

- `renovate.json` (D-11): `extends: ["config:recommended"]`, `minimumReleaseAge: "7 days"`,
  `rangeStrategy: "pin"`, one PR per package, a `packageRules` entry labeling `libsodium*` and
  `react-native-libsodium` PRs `needs-crypto-review` with automerge off for everything.
- `apps/client/public/manifest.json` (client.md §7, installable web app): `name` "Kodakiko",
  `short_name` "Kodakiko", `start_url` "/", `display` "standalone", `background_color`/`theme_color`
  = theme `background` light, icons 192 and 512 px (`apps/client/assets/icon-192.png`,
  `icon-512.png`, generated once from the app icon), linked from the web HTML through Expo's web
  config (`app.config.ts` `web.favicon` and a `+html.tsx` adding `<link rel="manifest">`).

## 7b. Implementation notes, phase 1 (2026-11-13, Opus)

- **Images (§1):** `infra/docker/api.Dockerfile` and `caddy.Dockerfile`, plus a root
  `.dockerignore`. Base images are pinned to version tags at least 7 days old (node `22.23.4`,
  caddy `2.11.8-builder`, rclone `1.75.2`; `docs/dependencies.md`). The floating tags named in §1
  had been rebuilt 2 days earlier. The API image was built and smoke-tested against the test
  Postgres: migrations ran, `/health/live` returned 200, `/api/v1/config` answered, and it ran with
  a read-only root filesystem (248 MB). **The Caddy image was not built (UNVERIFIED):** the Node
  client install plus the Go build need about 2–3 GB, and the build machine had 4.7 GB free with
  a 4 GB floor. The Caddyfile was checked with `caddy adapt` (stock `caddy:2`, with the DNS block
  swapped for `tls internal`): both sites parse.
- **Garage bootstrap:** the Garage image has no shell, so `bootstrap.sh` cannot run in it. The
  bootstrap is `infra/scripts/garage-bootstrap.ts`, which uses the Garage admin API v2
  (`GetClusterStatus`, `UpdateClusterLayout`/`ApplyClusterLayout`, `GetBucketInfo`/`CreateBucket`,
  `ListKeys`/`CreateKey`, `AllowBucketKey`). It runs as `garage-setup` in the pinned node image.
  Unit-tested with a fake API and run twice against real Garage v2.5.0 (idempotent; the secret is
  printed only when the key is created). Garage takes `GARAGE_RPC_SECRET` and `GARAGE_ADMIN_TOKEN`
  from env (verified), so `infra/garage/garage.toml` holds no secrets.
- **Compose (§3):** server settings live in `.env.api` (`.env.api.example`), not in `.env`.
  Otherwise `env_file: .env` would hand the DNS token, Garage admin token and Postgres password to
  the API container. `.env` (`.env.example`) holds only the compose variables. `DATABASE_URL` and
  `SERVER_VERSION` are set by compose. A `backup` profile service runs rclone. Validated with
  `docker compose config` (ports bound to `BIND_IP` only; no compose-only secret in `api`).
- **Backups (§4.5):** `infra/scripts/backup.sh` (dump → prune 14 daily + 8 weekly Sundays → rclone
  copy and bucket sync, with the Garage remote defined by `RCLONE_CONFIG_GARAGE_*` env) and
  `infra/systemd/kodakiko-backup.{service,timer}`. The prune rule was checked on dated files; the
  full script was not run against a live stack.
- **CSP hashes (§2.3)** and **`scripts/check-licenses.ts`** (needed by the §1.2 web stage) are done
  with tests. Root-level scripts get `vitest.scripts.config.ts` and `tsconfig.scripts.json`; the
  root `test` and `typecheck` scripts now include them. The real dependency tree passes D-70.
- **§7a:** `renovate.json`; web manifest and icons (`apps/client/public/manifest.json`,
  `assets/icon-192.png`/`icon-512.png`, generated with ImageMagick because no app icon existed).
  `+html.tsx` is used only for `static`/`server` output; with `single` output Expo reads
  `public/index.html` as the template (verified in `@expo/cli` 58.1.19 `webTemplate.js`), so the
  manifest link is there. `web.favicon` is set.
- `FAKE_PARAMS_SECRET` must be base64url; the operations guide gives
  `openssl rand -base64 32 | tr '+/' '-_' | tr -d '='`.

## 7c. Android release workflow brought forward (2026-11-13, programmer request)

The programmer asked for a GitHub Release of the Android APK **per tag only** (signed, `arm64-v8a`)
before the rest of phase 2. `.github/workflows/android-release.yml` (on `push: tags: ['v*']`) is the
Android part of §6:
1. Checks that the tag equals `v` + `app.config.ts` `version`.
2. Runs `pnpm verify`; installs NDK 27.0.12077973, CMake 3.22.1, platform 37 and build-tools 37.0.0.
3. `prebuild:android`, then `assembleRelease -PreactNativeArchitectures=arm64-v8a` with the
   `android.injected.signing.*` properties from the four secrets `ANDROID_KEYSTORE_BASE64`,
   `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` (keystore in a temp
   file, removed on exit).
4. Fails if `apksigner` shows the debug certificate. Otherwise publishes
   `kodakiko-<tag>-arm64-v8a.apk` and its `.sha256` with `gh release create --generate-notes`.

Not yet included from §6: image push, SBOM and licenses assets, and the full `ci.yml` jobs. UNVERIFIED
until the first tag runs: that the injected signing properties override the Expo template's debug
signing config (the `apksigner` step catches it if not), and that a release (R8/Hermes) build of
the app works at all — no release APK has been built yet (phase 1 builds debug only).

## 8. Tests and verification

- [ ] `pnpm verify`; CI green on a pull request (all five jobs).
- [ ] `docker compose up` on a clean VM in the VPN: first-run procedure §4.3 works end to end; the
      app (Android and web) signs up through the invite.
- [ ] Certificates issued via DNS-01 with ports 80/443 closed on the public interface
      (`curl` from outside the VPN fails; inside succeeds with a valid certificate).
- [ ] Presigned upload and download through `s3.kodakiko.<domain>` work (Host header).
- [ ] Backup timer ran once; restore drill on a second VM succeeded (paste `stats` before/after).
- [ ] CSP: no violations in Chrome DevTools on the main flows (sign in, gallery, entry, video,
      public link viewer).
- [ ] Release workflow on a test tag produces the APK, SBOM, licenses and image digests.
- [ ] Every item of the release checklist checked once.
