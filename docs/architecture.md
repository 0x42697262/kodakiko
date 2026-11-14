# Architecture

How the code is organized and how data moves through it. Decision numbers (D-NN) are ids from the
planning log, which is kept outside the repository; facts (F-XXX-NN) are in `docs/facts.md`.

## 1. System map

    ┌──────────────── Android app (Expo, native) ────────────────┐
    │ screens -> services -> core (pure) -> adapters (native)    │
    └──────────────┬──────────────────────────────┬──────────────┘
                   │ HTTPS (API, bearer token)    │ HTTPS (presigned S3 URLs)
                   ▼                              ▼
    ┌──────── API server (Fastify) ───────┐   ┌──── Object storage ────┐
    │ routes -> services -> domain -> db  │   │ Garage / B2 / R2       │
    │ presigns URLs, never sees plaintext │   │ ciphertext objects only│
    └──────────────┬──────────────────────┘   └────────────────────────┘
                   ▼                              ▲
              Postgres (ciphertext rows,          │ presigned URLs
              auth, shares, quotas, sync seq)     │
                   ▲                              │
    ┌──────────────┴──── Web app (same Expo codebase, React Native Web) ─┐
    │ screens -> services -> core -> adapters (web) + service worker     │
    └────────────────────────────────────────────────────────────────────┘

Remote access (D-36): devices reach the server only through a private mesh VPN (Tailscale,
NetBird, Headscale). The apps only know an HTTPS base URL for the API and one for S3.

## 2. What each party can see

| Party | Sees | Never sees |
|---|---|---|
| Object storage provider | Object sizes, object keys (opaque ids), upload/download times, client IPs via the VPN exit | Any plaintext: photos, videos, thumbnails, notes, tags, dates, locations |
| API server / operator | Usernames, user ids, counts and sizes of entries and media, collection membership, sync timing, Argon2id parameters, salts, public keys, wrapped (encrypted) keys, hash of the login key | Password, master key, any content or metadata plaintext, tag names, entry dates |
| Network (outside the VPN) | VPN traffic only | Everything else |
| Device at rest (locked) | Ciphertext rows and objects; Android plain thumbnails in app-private storage (D-12) | Master key (wrapped by Android Keystore / not persisted on web unless opted in) |

## 3. Repository layout (pnpm monorepo)

    kodakiko/
      package.json                 private root; scripts: verify, lint, typecheck, test
      pnpm-workspace.yaml          workspace list + all pnpm policy settings (D-11, F-TOOL-01..04)
      tsconfig.base.json           strict TypeScript settings shared by every package
      eslint.config.js             shared lint rules incl. no-comments, no magic strings
      apps/
        client/                    Expo app: Android + web from one codebase (D-07)
        server/                    Fastify API server
      packages/
        core/                      pure TypeScript: crypto scheme, byte formats, domain rules,
                                   local index, sync engine state machine. No I/O, no platform imports
        contract/                  Zod schemas for every API request/response, route paths,
                                   error codes. Imported by server and client
        native-upload/             Expo native module (Kotlin): Android foreground upload service (D-33)
      infra/
        compose/                   production, dev, test and UI-test Compose stacks
        docker/                    api.Dockerfile, caddy.Dockerfile
        caddy/, garage/            Caddyfile and entrypoint, garage.toml
        scripts/, systemd/         backup.sh, Garage bootstrap, CSP hashes; backup timer
      tests/
        e2e/                       two-device client scenarios against a real server
        ui/                        Playwright (web) and Maestro (Android) UI flows, screenshots
      docs/                        guide, operations, design reference, build history

Dependency direction (enforced by lint import rules, spec 01 §1.2; string placement in catalogs is
a review item, not a lint rule):

| Package | May import | Must not import |
|---|---|---|
| `packages/contract` | `zod` | anything else in the repo |
| `packages/core` | `packages/contract` (schemas, types, `ID_PATTERN`), `zod` | React, React Native, Expo, Node built-ins, browser globals, libsodium packages (crypto arrives through the `Sodium` port) |
| `packages/native-upload` | Expo modules API | `packages/core` |
| `apps/server` | `contract`, `core` (domain rules shared with the client only) | `apps/client` |
| `apps/client` | `contract`, `core`, `native-upload` | `apps/server` |

## 4. Layers per app

### 4.1 Server (`apps/server/src`)

| Folder | Layer | Job |
|---|---|---|
| `main.ts` | Boot | Load and validate config (fail fast), build the app through `app.ts`, listen, handle SIGTERM (drain requests, stop jobs, close pools) |
| `app.ts` | Composition root | Create db pool, repositories, storage presigner, clock, id generator, services, jobs; register routes |
| `config/` | Config | `settings.ts` (Zod env schema -> frozen typed `Settings`), `constants.ts` (internal tuning) |
| `http/routes/` | Entry | One file per resource; route definitions bound to `contract` schemas |
| `http/controllers/` | Entry | 5-15 lines each: validated input -> one service call -> response DTO |
| `http/*.ts` | Entry | request context (request id, HTTPS check), auth plugin (bearer token → user), rate limits, error handler (error class → `ErrorCode` + HTTP status), log redaction (spec 03 §2.2) |
| `services/` | Use cases | accounts, sessions, invites, collections, entries, media uploads, sync, sharing, public links, quotas, admin, account deletion |
| `domain/` | Pure rules | permissions per role, quota checks, sign-up policy (`signupMode`, D-27), Argon2id parameter floor, deletion schedules, object-key layout |
| `adapters/db/` | Adapter | Kysely repositories over `pg`; migrations in `adapters/db/migrations/` |
| `adapters/storage/` | Adapter | S3 presigner (single PUT, multipart create/part/complete/abort, GET with Range), delete |
| `jobs/` | Background | object garbage collection, account erase, stale multipart abort, trash purge; in-process scheduler guarded by `pg_try_advisory_lock` so one instance runs each job |
| `cli.ts` | Entry | operational commands in one dispatch map: `migrate-status` (spec 03), `create-invite` (spec 04), `apply-bucket-cors` (spec 05), admin commands (spec 19). No command creates an account: keys are generated on clients (D-47) |
| `text/` | Catalog | log event names, error messages |

The server has **no libsodium dependency**: it hashes the login key with `node:crypto` SHA-256
(F-TOOL-09) and generates tokens with `node:crypto.randomBytes`.

Build and run: `esbuild` bundles `src/main.ts` and `src/cli.ts` (with the workspace packages
`contract` and `core`) into `dist/main.js` and `dist/cli.js`, `--platform=node --format=esm`,
external: `pg-native`. The Docker image runs `node dist/main.js`. Tests run the TypeScript
sources through vitest. Reason: Node's type stripping does not cover every case of TypeScript
inside linked workspace packages, and one bundled file starts faster and ships fewer files.

### 4.2 Client (`apps/client`)

| Folder | Layer | Job |
|---|---|---|
| `app/` | Entry (Expo Router routes) | Thin screens: read view-model hooks, render feature components |
| `src/composition/` | Composition root | `createAppContext()` (async): builds the platform's adapters (Metro picks `*.native.ts` / `*.web.ts`), services, the sync engine; provided through one React context (spec 07a §5) |
| `src/config/` | Config | client constants and the server base URL resolution (web: same origin; Android: chosen at first run, `client.md` §7.1); server-provided `PublicConfigDto` validated with Zod |
| `src/features/<feature>/` | UI | components, hooks and view-model per feature: `gallery`, `calendar`, `capture`, `entries` (edit page, note editor, crop, media menu), `viewer`, `video`, `voice`, `markdown`, `tags`, `sharing`, `links`, `link-viewer`, `settings`, `more`, `auth`, `lock`, `admin`, `sync`, `diagnostics` |
| `src/services/` | Use cases | auth, entries, media import, index, sync, originals and thumbnails caches, video, sharing, public links, export and journal import, settings, lock, admin |
| `src/navigation/` | Navigation | `useRouter` wrapper that drops a repeated push to the same page within 600 ms |
| `src/ui/` | Shared UI | `Screen`, `AppBar`, `Touchable` (ripple), Paper-based `Button`, preference list, snackbar host, haptics, theme and dynamic color, system insets |
| `src/adapters/` | Adapters | one interface per port, two files per port: `*.native.ts` and `*.web.ts` (Metro and the web bundler pick by suffix) |
| `src/text/`, `src/keys/`, `src/constants/` | Catalogs | UI strings, ids/keys, tuning |
| `web/sw/`, `web/workers/` | Web only | service worker for video range decryption (D-32) and the Argon2 worker, bundled with esbuild into `public/` |

Client ports and their implementations:

| Port | Android | Web |
|---|---|---|
| `Sodium` | `react-native-libsodium` (JSI) | `libsodium-wrappers-sumo` (WASM) |
| `FileStore` (ciphertext objects, chunk read/write) | `expo-file-system` `File` + `FileHandle` (F-LIB-05) | OPFS (`navigator.storage.getDirectory()`) |
| `RowStore` (ciphertext rows, outbox, cursors, settings) | `expo-sqlite` (plain; contents are ciphertext, D-37) | IndexedDB |
| `KeyVault` (wrapped master key, lock state) | `expo-secure-store` + `expo-local-authentication` (F-LIB-13) | memory only; opt-in wrap with a non-extractable WebCrypto key in IndexedDB (D-26) |
| `Camera` | `react-native-vision-camera` (F-LIB-06) | `getUserMedia` + `MediaRecorder` |
| `MediaPicker` | `expo-image-picker` (Android Photo Picker, F-LIB-12) | file input, drag and drop, clipboard paste (D-20) |
| `Uploader` | `packages/native-upload` foreground service (D-33) | in-page `fetch` queue |
| `VideoSource` | decrypt to temp file in app cache (D-32) | service worker URL (D-32) |
| `ThumbnailCache` | plain files in app-private storage by default, encrypted on opt-in (D-12) | encrypted in OPFS, decrypted to object URLs in memory |
| `Http` | `fetch` with timeout and retry policy from `core` | same |
| `ShareFiles` (share to apps, save to device) | `react-native-share` | `navigator.share({ files })`, download link |
| `ClipboardImage` | `expo-clipboard` | DOM `paste` events |
| `VoiceRecorder` | `expo-audio` (AAC) | `MediaRecorder` (Opus) |
| `ImageCrop` | `expo-image-manipulator` | canvas |
| `NetworkInfo` | Wi-Fi state | always Wi-Fi |

## 5. Data flow of the main use cases

### 5.1 Capture and save an entry (offline-first, D-06)

1. Camera or picker returns plaintext media files in the app's temp directory.
2. For each media item: generate thumbnail (and video poster frame) on device; read EXIF capture
   time and GPS (D-20, D-25).
3. Encrypt each original and thumbnail into the local `FileStore` (STREAM format,
   `docs/design/crypto.md` section 7); delete the plaintext temp files.
4. Encrypt the entry metadata (note, tags, time, timezone, location, media order) with the entry
   key; write the ciphertext row and an outbox item to `RowStore` in one transaction.
5. Update the in-memory index; the gallery shows the entry immediately.
6. The edit page then writes every change (note after a 600 ms pause, everything else at once)
   as a new revision of the metadata, each with its own outbox item. Voice notes, crops and
   pasted pictures go through the same import path as camera items.
7. The sync engine drains the outbox when online: create entry row on the server, upload objects
   through presigned URLs (multipart for large files), confirm upload. Every step is idempotent by
   client-generated ids.

### 5.2 Browse and filter

1. On unlock: decrypt every entry's metadata and build the in-memory index (spec 11; the encrypted
   snapshot of D-46 is reserved, not built in v1).
2. Gallery and calendar read from the index; thumbnails load from `ThumbnailCache`, downloading
   and decrypting missing ones on demand with bounded concurrency.

### 5.3 Sync pull

1. Client calls `GET /sync/state` (all readable collections with their sequence numbers), then
   pulls each changed collection's diff from its own cursor (spec 08).
2. Server returns changed rows (ciphertext) ordered by a monotonic sequence, bounded page size,
   `hasMore` flag (F-ENTE-17, but sequence instead of timestamp).
3. Client stores rows, decrypts metadata into the index, advances cursors.

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
| Unit | `*/test/*.test.ts` in each package | `pnpm verify` (with lint and typecheck) |
| Server integration | `apps/server/test/integration` | Postgres and Garage from the test stack |
| Client scenarios | `tests/e2e` | Two simulated devices against a real server |
| UI flows | `tests/ui/web/specs/U-NN-*.spec.ts`, `tests/ui/android/flows` | Playwright on desktop and phone viewports; Maestro on the emulator |

The flow catalog (U-01 …) is in `docs/features/07c-ui-test-harness.md`; behaviors in
`docs/features/behaviors.md`. More in [testing.md](testing.md).
