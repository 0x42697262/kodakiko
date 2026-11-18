# Architecture

How the code is organized and how data moves through it. Decision numbers (D-NN) are ids from the
planning log, which is kept outside the repository; facts (F-XXX-NN) are in `docs/facts.md`.

## 1. System map

    ┌──────── Android app (Kotlin, Jetpack Compose, apps/android) ────────┐
    │ :ui:* screens -> :service:* -> :shared:* (pure) ; :data:* / :platform:* │
    └──────────────┬──────────────────────────────┬────────────────────────┘
                   │ HTTPS (API, bearer token)    │ HTTPS (presigned S3 URLs)
                   ▼                              ▼
    ┌──────── API server (Fastify) ───────┐   ┌──── Object storage ────┐
    │ routes -> services -> domain -> db  │   │ Garage / B2 / R2       │
    │ presigns URLs, never sees plaintext │   │ ciphertext objects only│
    └──────────────┬──────────────────────┘   └────────────────────────┘
                   ▼                              ▲
              Postgres (ciphertext rows,          │ presigned URLs
              auth, shares, quotas, sync seq)     │
                                                  │
    ┌──── Public link viewer (apps/link-viewer, any browser) ────┐
    │ /s/<id>#<secret>: TypeScript + DOM page, core crypto,       │
    │ media service worker for video; read-only, no account       │
    └─────────────────────────────────────────────────────────────┘

Caddy serves the API, the link viewer at `/s/*` and a "Get the Android app" page everywhere else.
There is no web app since v0.4.0 (the Expo / React Native client of v0.1–v0.3 was removed in
v0.4.1). Remote access (D-36): devices reach the server only through a private mesh VPN (Tailscale,
NetBird, Headscale), unless public links are opened up (operations guide §8). The app only knows
an HTTPS base URL for the API and one for S3.

## 2. What each party can see

| Party | Sees | Never sees |
|---|---|---|
| Object storage provider | Object sizes, object keys (opaque ids), upload/download times, client IPs via the VPN exit | Any plaintext: photos, videos, thumbnails, notes, tags, dates, locations |
| API server / operator | Usernames, user ids, counts and sizes of entries and media, collection membership, sync timing, Argon2id parameters, salts, public keys, wrapped (encrypted) keys, hash of the login key | Password, master key, any content or metadata plaintext, tag names, entry dates |
| Network (outside the VPN) | VPN traffic only | Everything else |
| Phone at rest (locked) | Ciphertext rows and objects; plain thumbnails in app-private storage unless encrypted thumbnails are on (D-12); the debug log (ids, counts, timings, no content) | Master key (wrapped by an Android Keystore key, [encryption.md](encryption.md#where-the-keys-live)) |

## 3. Repository layout (pnpm monorepo + Gradle build)

    kodakiko/
      package.json                 private root; scripts: verify, lint, typecheck, test, ui:*
      pnpm-workspace.yaml          workspace list + all pnpm policy settings (D-11, F-TOOL-01..04)
      tsconfig.base.json           strict TypeScript settings shared by every package
      eslint.config.js             shared lint rules incl. no-comments, no magic strings
      flake.nix                    Android SDK shell (nix develop)
      apps/
        android/                   Kotlin Android app (Gradle, see §4.2)
        server/                    Fastify API server
        link-viewer/               standalone public link page (TypeScript + DOM, esbuild)
      packages/
        core/                      pure TypeScript: crypto scheme, byte formats, domain rules, export
                                   format; used by the server, the link viewer, tests and
                                   decrypt-export, and the reference the Kotlin port is tested against
        contract/                  Zod schemas for every API request/response, route paths,
                                   error codes. Imported by the server and the link viewer
      infra/
        compose/                   production, dev, test and UI-test Compose stacks
        docker/                    api.Dockerfile, caddy.Dockerfile (builds the link viewer)
        caddy/, garage/            Caddyfile and entrypoint, garage.toml
        scripts/, systemd/         backup.sh, Garage bootstrap; backup timer
      tests/
        e2e/support/               Node launcher of the Kotlin end-to-end tests (apps/android/e2e)
        ui/                        Maestro (Android) flows, the link-viewer Playwright spec, seeding
        fixtures/                  media, v0.3.1 device fixtures, golden files for the Kotlin port,
                                   UI strings generator
      docs/                        guide, operations, features, design reference, ADRs

Dependency direction for the TypeScript packages (enforced by lint import rules):

| Package | May import | Must not import |
|---|---|---|
| `packages/contract` | `zod` | anything else in the repo |
| `packages/core` | `packages/contract` (schemas, types, `ID_PATTERN`), `zod` | Node built-ins, browser globals, libsodium packages (crypto arrives through the `Sodium` port) |
| `apps/server` | `contract`, `core` | `apps/link-viewer` |
| `apps/link-viewer` | `contract`, `core`, `libsodium-wrappers-sumo` | `apps/server` |

The Android app shares no code with the TypeScript packages at build time. Its `:shared:*` modules
are Kotlin ports of `packages/core` and `packages/contract`; golden files generated from the
TypeScript code (`tests/fixtures/golden*`) and reverse checks keep both byte-identical.

## 4. Layers per app

### 4.1 Server (`apps/server/src`)

| Folder | Layer | Job |
|---|---|---|
| `main.ts` | Boot | Load and validate config (fail fast), build the app through `app.ts`, listen, handle SIGTERM (drain requests, stop jobs, close pools) |
| `app.ts` | Composition root | Create db pool, repositories, storage presigner, clock, id generator, services, jobs; register routes |
| `config/` | Config | `settings.ts` (Zod env schema -> frozen typed `Settings`), `constants.ts` (internal tuning) |
| `http/routes/` | Entry | One file per resource; route definitions bound to `contract` schemas |
| `http/controllers/` | Entry | 5-15 lines each: validated input -> one service call -> response DTO |
| `http/*.ts` | Entry | request context (request id, HTTPS check), auth plugin (bearer token → user), rate limits, error handler (error class → `ErrorCode` + HTTP status), log redaction |
| `services/` | Use cases | accounts, sessions, invites, collections, entries, media uploads, sync, sharing, public links, quotas, admin, account deletion |
| `domain/` | Pure rules | permissions per role, quota checks, sign-up policy (`signupMode`, D-27), Argon2id parameter floor, deletion schedules, object-key layout |
| `adapters/db/` | Adapter | Kysely repositories over `pg`; migrations in `adapters/db/migrations/` |
| `adapters/storage/` | Adapter | S3 presigner (single PUT, multipart create/part/complete/abort, GET with Range), delete |
| `jobs/` | Background | object garbage collection, account erase, stale multipart abort, trash purge; in-process scheduler guarded by `pg_try_advisory_lock` so one instance runs each job |
| `cli.ts` | Entry | operational commands in one dispatch map: `migrate-status`, `create-invite`, `apply-bucket-cors`, admin commands. No command creates an account: keys are generated on clients (D-47) |
| `text/` | Catalog | log event names, error messages |

The server has **no libsodium dependency**: it hashes the login key with `node:crypto` SHA-256
(F-TOOL-09) and generates tokens with `node:crypto.randomBytes`.

Build and run: `esbuild` bundles `src/main.ts` and `src/cli.ts` (with the workspace packages
`contract` and `core`) into `dist/main.js` and `dist/cli.js`, `--platform=node --format=esm`,
external: `pg-native`. The Docker image runs `node dist/main.js`. Tests run the TypeScript
sources through vitest. Reason: Node's type stripping does not cover every case of TypeScript
inside linked workspace packages, and one bundled file starts faster and ships fewer files.

### 4.2 Android app (`apps/android`)

Package root `dev.kodakiko`; application id `com.x42697262.kodakiko` (debug and preview builds add
`.next`). Gradle modules in layers; a module depends only on the layers below it, and feature
modules never depend on each other (`tooling-tests` checks the Gradle files):

    :app ──► :ui:feature:* ──► :ui:core
      │            │
      └──────► :service:* ──► :data:* / :platform:* ──► :shared:*

| Layer | Modules | Job |
|---|---|---|
| Shared core (Kotlin Multiplatform `commonMain`, no Android or Java APIs) | `:shared:crypto` (libsodium port, sealed blobs, STREAM, recovery words, share payload), `:shared:js` (JavaScript date/text/number semantics the ports need), `:shared:contract` (DTOs, payloads, routes, errors), `:shared:account` (KDF policy, sign-up/in, key bundle, recovery, password change), `:shared:domain` (entry time, calendar, tags, entry metadata and edits), `:shared:media` (sniffing, EXIF, ISO-BMFF, capture time, metadata stripping), `:shared:index` (gallery index, filters, search, grouping), `:shared:sync` (failure classes, push plan, pull decisions, three-way merge), `:shared:export` (export layout, `entry.md`, KDKX, ZIP), `:shared:markdown` (note parser with v0.3.1's limits, plain-text copy), `:shared:log` (debug-log port and privacy-safe line format), `:shared:testing` (golden replay for tests) | Pure rules and byte formats, golden-tested against `packages/core` |
| Data (Android I/O) | `:data:db` (SQLDelight over the v0.3.1 SQLite file, key-value store, device settings), `:data:files` (paths, encrypted object files, temp files), `:data:secure` (Keystore vault, session and global stores, v0.3.1 secure-store reader), `:data:migration` (one-time adoption of a v0.3.1 install), `:data:net` (OkHttp API client, network monitor), `:data:upload` (part uploader, upload registry; runs in the app process), `:data:media` (STREAM file reader, decrypting Media3 data source, Coil fetchers, thumbnail and originals caches, download queue, probes, thumbnail encoder), `:data:log` (debug-log file under `files/logs`) | Storage, network and media I/O, all off the main thread |
| Platform (device features) | `:platform:camera` (CameraX), `:platform:picker` (Photo Picker, document and folder pickers, FileProvider, share intents), `:platform:audio` (voice recorder and player), `:platform:location` (one-shot position), `:platform:device` (haptics, clipboard image, secure screen, system bars, foreground tracker, notification permission) | Thin wrappers over Android APIs |
| Services (use cases) | `:service:account`, `:service:lock`, `:service:entries` (entries, imports, capture tray), `:service:index`, `:service:sync`, `:service:sharing`, `:service:links`, `:service:backup` (export and restore), `:service:shareout` (share to apps), `:service:admin`, `:service:stats`, `:service:diagnostics` | Business logic over data and platform; an interface plus one implementation each |
| UI | `:ui:core` (theme and color presets, Mihon-based components, settings widgets and search, `Route` keys, strings, `TestTags`, formatting, markdown view), `:ui:feature:auth`, `lock`, `gallery`, `calendar`, `viewer`, `edit`, `capture`, `tags`, `sharing`, `links`, `settings`, `more` | Compose screens: `XScreen` (ViewModel, events, navigation) + stateless `XContent` |
| App | `:app` | `MainActivity`, Voyager navigator and home tabs, route guards, Metro `AppGraph`, adapters that implement the screens' ports over services, lock and unlock steps, crash recorder and debug-log wiring, manifest |
| Tests | `:e2e`, `:tooling-tests` | Services against the real server; source rules, module boundaries, catalog allowlist, release build checks |

Screens follow Mihon: a ViewModel exposes `StateFlow<XState>` (state classes are `@Immutable`),
one-shot events go through a `Channel`, and dialogs are a sealed value in the state. Feature
modules declare small ports (interfaces) for what they need; `:app` implements them over the
services and binds everything in the Metro graph. Crypto, file, database and network work runs on
IO dispatchers (Argon2id on its own thread); the main thread never does I/O.

Ports and their Android implementations:

| Port | Implementation |
|---|---|
| `Sodium` | lazysodium 5.2.0 (libsodium 1.0.20 through JNA, D-110) behind an `expect`/`actual` |
| Row store (ciphertext rows, outbox, cursors, settings) | SQLDelight on the framework SQLite, the v0.3.1 file and schema opened in place (F-KT-19) |
| File store (ciphertext objects) | app-private files, atomic writes, positional reads (`StreamFileReader`) |
| Key vault | Android Keystore AES-GCM keys, biometric-bound or device-credential gated (F-KT-25) |
| Camera | CameraX (photo JPEG 0.92, MP4 video with a size cap, F-KT-27) |
| Media picker | Android Photo Picker; documents and folders through the Storage Access Framework |
| Uploader | `:data:upload` part uploader on OkHttp, in the app process (no foreground service yet, D-168) |
| Video and voice playback | Media3 ExoPlayer over a decrypting `DataSource` (no plaintext file, F-KT-18) |
| Thumbnails and originals in images | Coil 3 with decrypting fetchers, no disk cache |
| HTTP | OkHttp 5, one shared client; redirects off for the API |
| Share to apps | decrypted copies in `cache/share`, FileProvider, `ACTION_SEND(_MULTIPLE)` chooser |
| Clipboard image | `ClipboardManager`, re-encoded as JPEG |
| Voice recorder | `MediaRecorder` (AAC in `.m4a`, 10-minute cap) |
| Image crop | Android bitmap decode and JPEG 0.92 encode |
| Network info | `ConnectivityManager` default-network callback (online, unmetered) |
| Delete from device (v0.4.1) | `MediaStore.createDeleteRequest` with the system confirmation (F-AND-03) |

## 5. Data flow of the main use cases

### 5.1 Capture and save an entry (offline-first, D-06)

1. The camera (CameraX) or the Photo Picker gives plaintext media files, copied into the app's
   temp directory (`cache/kodakiko/<account>/tmp`).
2. For each media item: generate thumbnail (and video poster frame) on device; read EXIF capture
   time and GPS (D-20, D-25).
3. Encrypt each original and thumbnail into the local file store (STREAM format,
   `docs/design/crypto.md` section 7). The plaintext temp files are wiped at the next lock (and at
   app start and unlock, in case the app was killed).
4. Encrypt the entry metadata (note, tags, time, timezone, location, media order) with the entry
   key; write the ciphertext row and an outbox item to the database in one transaction.
5. Update the in-memory index; the gallery shows the entry immediately.
6. The edit page keeps changes in a draft and writes them as one new revision of the metadata
   (with its outbox items) when the person taps Done or Save, or when the app locks (v0.4.1; v0.3.1
   autosaved every change). Discard puts the entry back and removes media added in the session; on
   a new entry it deletes the entry. Voice notes, crops and pasted pictures go through the same
   import path as camera items. After a save, the app may offer to delete gallery originals from
   the phone (Android's own confirmation, [features/capture.md](features/capture.md)).
7. The sync engine drains the outbox when online: create entry row on the server, upload objects
   through presigned URLs (multipart for large files), confirm upload. Every step is idempotent by
   client-generated ids.

### 5.2 Browse and filter

1. On unlock: decrypt every entry's metadata and build the in-memory index (the encrypted
   snapshot of D-46 is reserved, not built in v1).
2. Gallery and calendar read from the index; thumbnails load through the Coil fetchers and the
   thumbnail cache, downloading and decrypting missing ones on demand (6 thumbnails and 2 originals
   at a time).

### 5.3 Sync pull

1. Client calls `GET /sync/state` (all readable collections with their sequence numbers), then
   pulls each changed collection's diff from its own cursor ([features/sync.md](features/sync.md)).
2. Server returns changed rows (ciphertext) ordered by a monotonic sequence, bounded page size,
   `hasMore` flag (F-ENTE-17, but sequence instead of timestamp).
3. Client stores rows, decrypts metadata into the index, advances cursors.

### 5.4 Lock and unlock

1. Before the account state turns locked, an open edit page is saved (5 s limit) and unlocked
   work (in-flight crypto, sharing and link writes) is cancelled and joined.
2. Then the lock steps run in order: cancel export, stop sync, cancel downloads, release video,
   release originals, clear thumbnails, clear the capture tray, wipe temp files, clear the index,
   clear UI state, clear collection names, clear the account, flush edits, wipe keys; then temp
   files are wiped once more. A failed step is logged and skipped; the last one always runs.
3. Unlock steps: load settings (and wipe temp files), start sync, build the index, sweep orphan
   files, enforce the originals cache cap, refresh the account. Each step's duration goes to the
   debug log when it is on ([features/lock.md](features/lock.md)).

## 6. Cross-cutting rules

| Topic | Rule |
|---|---|
| Ids | Client generates entry, media and object-version ids (128-bit random, base64url) so retries are idempotent |
| Objects | Immutable: a changed file gets a new object key; old objects are deleted only by the GC job after trash expiry plus grace (D-34) |
| Auth | Opaque bearer token in the `Authorization` header, never cookies (no CSRF); server stores only SHA-256 of the token |
| Timeouts | Every outbound call (client -> API, client -> S3, server -> S3, server -> Postgres) has a configured timeout |
| Retries | Only idempotent calls (GET, PUT of an immutable object, idempotent creates keyed by client id); exponential backoff with jitter and a cap; never retry 401/403/404/409/422 |
| Bounds | Every list endpoint paginates with a hard max page size; every upload has a size cap from server config (D-19) |
| Logging | Server JSON logs (pino via Fastify) with request id, user id, event name; tokens, keys, auth headers and bodies redacted |
| Flags | `sharing` (D-04), `public_links` (D-10), `signupMode` (D-27): server config, off/`invite` by default; registry in `docs/design/deployment.md` |
| Versioning | Every encrypted blob and every stored JSON carries `schemaVersion`; readers migrate old versions on read |

## 7. Testing

| Layer | Where | Runs |
|---|---|---|
| Kotlin unit, Compose and screenshot tests | `apps/android/**/src/test`, `commonTest`/`jvmTest` | `./gradlew verify` |
| Golden replays | `tests/fixtures/golden*` read by the `:shared:*` tests | `./gradlew verify` |
| TypeScript unit | `*/test/*.test.ts` in each package | `pnpm verify` (with lint and typecheck) |
| Server integration | `apps/server/test/integration` | Postgres and Garage from the test stack |
| End-to-end | `apps/android/e2e` | `./gradlew e2e`: two simulated devices on the Kotlin services against a real server |
| Device tests | `src/androidTest`, `src/androidDeviceTest` | on the emulator |
| UI flows | `tests/ui/android/flows`, `tests/ui/web/specs/U-18-link-viewer.spec.ts` | Maestro on the emulator; Playwright for the link viewer |

The flow catalog (U-01 …) is in [testing.md](testing.md#flow-catalog); behaviors in
[behaviors.md](behaviors.md).
