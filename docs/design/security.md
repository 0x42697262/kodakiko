# Security

Design reference written before the build (2026-11-11). Where it differs from the code, the code
and the feature specs' implementation notes are right.

Normative for the threat model, the security-relevant behavior of every component, and the hardening checklist. Crypto details: `docs/design/crypto.md`.

## 1. Threat model (D-29)

### 1.1 Protected against

| Adversary | Capability | Defense |
|---|---|---|
| Object storage provider / bucket compromise | Reads every object and key name | All objects are ciphertext; keys are opaque ids (`storage.md` §2). Traffic metadata (sizes, timing) leaks; content does not |
| Server operator / server compromise (DB dump or live process) | Reads every row, can serve malicious responses | Rows hold only wrapped keys and sealed blobs; no password, no MK, no plaintext (`architecture.md` §2). Login stores only SHA-256 of a 256-bit key (F-TOOL-09). A malicious server can refuse service or serve stale data, but cannot decrypt. A malicious server *can* substitute a recipient's public key when sharing — mitigated by out-of-band fingerprint comparison (`crypto.md` §8.1), documented as a residual risk |
| Network attacker | Intercept/modify traffic | HTTPS everywhere (D-29); on the private profile, traffic additionally never leaves the VPN (D-36) |
| Attacker with a stolen **locked** phone | File-system access | Android file-based encryption + app-private storage; RowStore and objects are ciphertext; MK is in Keystore-backed Secure Store, biometric-gated when available (`crypto.md` §9.1); plain thumbnail cache is the accepted exposure (D-12), mitigated by `allowBackup=false` and the encrypt-thumbnails toggle |
| Attacker with a stolen **unlocked** phone while the app is unlocked | Everything the user can see | Auto-lock timers (D-26). Not fully defendable; stated |
| Password guessing (offline, after DB leak) | Hash of login key + Argon2id params | Offline cost = Argon2id at ≥1 GiB×4 work (`crypto.md` §3 floor). Online guessing is rate-limited (§4) |
| Brute force of a public link | Link ids | 256-bit link secret in the URL fragment; the server stores only SHA-256 of the derived access token, and link views are rate-limited per link id and per IP |
| Malicious media file | Crafted JPEG/MP4 | Parsing is limited to a bounded header reader (`media.md` §4.1); decode happens in platform codecs on a copy in `tmp/`; no server-side parsing at all |

### 1.2 Not protected against (documented limitations)

1. A device that is unlocked and compromised (malware with app privileges): it can read anything the
   app can decrypt while unlocked.
2. Someone who knows the password and recovery key. The recovery key equals full access; treat it
   like the password (guidelines 9.1 "password-equivalent").
3. Metadata leakage to the storage provider and server: object sizes, counts, upload cadence, and
   per-user storage totals are visible. Padded metadata blobs (256-byte blocks, `crypto.md` §6.2)
   hide note lengths only coarsely.
4. A removed share recipient or a revoked link viewer who already downloaded content (D-31,
   F-1P-05). The UI states this when sharing.
5. Screen observers, screenshots, and OS-level screen recording. No screenshot-blocking in v1
   (Android `FLAG_SECURE` is a candidate later; it breaks legitimate use and is not requested).
6. Compromise of the client dependency chain at build time: mitigated by D-11 (allowlist,
   blocked install scripts, OSV scanning, SBOM), not eliminated.
7. A malicious server can withhold changes or serve an older ciphertext of an entry (rollback).
   Clients never apply a pulled revision lower than the one they hold (`sync.md` §4.1), so a
   device that already saw a newer version is not rolled back; a fresh device cannot detect it.
8. Any signed-in user can test whether a username exists through the public-key lookup
   (`api.md` §4). Accepted for invite-only servers; review before `signupMode = open`.

## 2. Key-material handling rules

1. Plaintext keys exist only in client memory (and in the Android Keystore-wrapped Secure Store
   item). Never in logs, never in crash reports, never in RowStore plaintext columns, never in URLs
   or query strings (link secrets are in fragments only).
2. Server: no crypto library, no key material beyond hashes and wrapped blobs it cannot open. The
   only secret it holds is its own config (DB password, bucket credentials, HMAC secret for fake
   salts, session-signing nothing — tokens are opaque random).
3. Zeroing: buffers holding keys are `fill(0)`ed after use, best effort (JS gives no guarantee;
   `crypto.md` §1).
4. Recovery key display: shown once at sign-up with a confirm-3-words gate; re-display requires the
   password and derives nothing new (`crypto.md` §5.5). Never sent to the server in word form.
5. The service worker holds per-playback media keys in memory only, keyed by playback id, dropped on
   lock (`crypto.md` §9.2, `storage.md` §5).

## 3. Transport and headers

Web app responses (static files served by Caddy, configured in spec 21) carry the headers below. API
responses (Fastify with `@fastify/helmet`) carry the same headers except that their CSP is
`default-src 'none'; frame-ancestors 'none'` (JSON never needs to load anything).

| Header | Value |
|---|---|
| `Strict-Transport-Security` | `max-age=63072000; includeSubDomains; preload` |
| `Content-Security-Policy` | app host: `default-src 'self'; script-src 'self' 'wasm-unsafe-eval' <sha256 hashes of inline scripts>; worker-src 'self'; connect-src 'self' https://s3.kodakiko.<domain>; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'` — `connect-src` includes the bucket host for direct upload/download; `'wasm-unsafe-eval'` is required because libsodium runs as WebAssembly (it allows compiling WebAssembly only, not JavaScript `eval`); inline scripts of the Expo web export are allowed by hash, computed at build time (spec 21 §2.3), never with `'unsafe-inline'`; `style-src 'unsafe-inline'` is needed because React Native Web injects styles at runtime (styles cannot execute code) |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `no-referrer` (keeps `/s/<linkId>` paths and any fragment out of referrers) |
| `Permissions-Policy` | `camera=(self), microphone=(self), geolocation=(self)` |
| `Cross-Origin-Opener-Policy` / `Cross-Origin-Embedder-Policy` | not set (not needed, F-LIB-07/client.md §7); `Cross-Origin-Resource-Policy: same-origin` on non-public routes |

Client (Android app config): `usesCleartextTraffic=false`; camera/microphone/location permissions
declared with purpose strings; `allowBackup=false` and no backup rules (D-12, F-LIB-13 notes
Secure Store is excluded automatically, but the plain thumbnail cache must never enter a device
backup).

## 4. Rate limiting and abuse controls (D-41)

Per `api.md` §1.1. Additional rules:

1. Sign-in failures: after `settings.auth.maxFailedSignIns` (default 10) per username per hour,
   add a progressive delay (exponential, capped at 30 s) before responding; log `auth.throttled`.
2. Sign-up: invite required (`signupMode = 'invite'`, D-27); the open mode adds a per-IP daily cap.
3. Public-link views: per link id and per IP; `max_views` enforcement is transactional
   (`UPDATE … SET view_count = view_count + 1 WHERE view_count < max_views`), so concurrent viewers
   cannot exceed it.
4. Presign endpoints: per-user caps per hour (`settings.limits.maxPresignsPerHour`, default 1000)
   to bound bucket-side garbage; `abort-stale-uploads` cleans the rest.
5. All limits are config, not code (guidelines 4.2/4.3).

## 5. Server hardening checklist

1. Config validated at boot; missing secrets fail startup (guidelines 4.3).
2. No admin route returns key material or blobs belonging to other users (`api.md` §10).
3. Ownership checks in services, not controllers (guidelines 3.1): every entry/media/object access
   resolves through membership; one `NOT_FOUND` for missing/forbidden.
4. SQL only through Kysely (no string-built queries); migrations only (guidelines 4.4).
5. Logging: pino, structured, `requestId` + `userId` + event name from the catalog; redact
   `authorization`, `x-kodakiko-link-token`, `password`, `loginKey`, `token`, and any `*_key*`
   fields (guidelines 7.3/C-6). Decision logs (`object.presigned`, `share.created`, `link.created`,
   `link.revoked`, `admin.*`, `auth.throttled`, `job.*`, `usage.reconciled`) carry inputs minus
   secrets; a quota refusal is the `object.presigned` path returning `QUOTA_EXCEEDED`, logged as
   `object.quota_refused` (spec 05).
6. Health endpoints outside the rate limiter; `/health/ready` checks DB and bucket with timeouts.
7. Background jobs advisory-locked, batched, idempotent (`data-model.md` §5).
8. HTTPS termination at Caddy; the API listens on the compose network only, never on a public
   interface (`deployment.md`).
9. Dependencies: allowlist, OSV gate, SBOM per release (D-11).
10. Docker images: pinned digests, non-root user, read-only rootfs where possible.

## 6. Account lifecycle

| Event | Behavior |
|---|---|
| Sign-up | Invite consumed in the same transaction as user creation; KDF floor enforced server-side (reject `KDF_PARAMS_TOO_WEAK`) |
| Delete request | `status = 'pending_deletion'`, `deletion_due_at = now + settings.accounts.deletionGraceDays` (7); **all sessions revoked immediately** (D-29). Signing in still works but only reaches `/cancel-deletion`; only session check, sign-out, `/users/me` and cancel work (`api.md` §4) |
| Cancel | `POST /users/me/delete/cancel` inside the window restores `active` |
| Erase | `erase-accounts` job: objects → `deleting` (immediate `delete_after`), then rows cascade; `collect-objects` removes bucket data. The username becomes reusable only after erase completes |
| Admin deletion of other accounts | Not in v1: users delete their own accounts; admins manage quotas, roles and invites only (spec 19) |
| Session hygiene | Password change and recovery revoke all other sessions; idle expiry per `settings.auth.sessionIdleDays` (default 30) |

## 7. Verification checklist (security)

Concrete checks, to be mirrored in each feature spec (guidelines 11):

1. Sign-up with `memLimitBytes` below the floor → `422 KDF_PARAMS_TOO_WEAK`, no user row.
2. Sign-in with a wrong login key → `401 INVALID_CREDENTIALS`, identical response shape and
   timing class for an unknown username (`/auth/params` fake-salt path exercised).
3. DB dump drill: `pg_dump` a test database; grep for any tag string, note text or base64 of known
   plaintext → zero hits.
4. Bucket drill: download an object with `rclone`; first 24 bytes are `KDKS` + version; the rest
   fails AEAD without the media key.
5. Tamper drill: flip one byte of a stored `metadata_blob` → client shows a decrypt error, no
   crash, entry marked unreadable.
6. Presign without membership → `404 NOT_FOUND` (single code).
7. Concurrent `max_views = 1` link: 5 parallel fetches → exactly 1 gets `200`, others `403`.
8. Lock drill: background the app past the timer → memory snapshot contains no 32-byte key equal to
   MK (manual test with a debug build that can dump the key for comparison).
9. `allowBackup=false`: `adb backup` produces no app data (once per release, manual).
10. Headers: `curl -I https://<host>/` shows HSTS, CSP, nosniff, no-referrer.

Limitation (D-78): the API's storage key owns the bucket (needed to apply the CORS rule). A
compromised API host can change bucket settings as well as delete objects; backups (rclone to a
separate remote) are the recovery path.
