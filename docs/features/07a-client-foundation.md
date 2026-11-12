# 07a — Client foundation: app shell, composition root, platform adapters

Status: draft 1 (2026-11-11).

Goal: the Expo app (Android dev build + web) with Expo Router, theme, text catalog, the
composition root, and the platform adapters every later client spec uses: `Sodium`, `RowStore`,
`FileStore`, `KeyVault`, `SessionStore`, `Http`, `Clock`. Plus a developer diagnostics screen
that runs the crypto vectors on the device (D-11 equivalence proof) and measures Argon2id
(RISK-1). Auth screens are spec 07b.

Read first: `docs/architecture.md` §4.2; `docs/design/client.md` (all);
`docs/design/data-model.md` §4; `docs/design/crypto.md` §§2, 9; `docs/design/security.md` §3
(Android settings). Depends on specs 02 and 04 (contract auth schemas, core account flows).

Client conventions (for every later client spec):

- Imports inside `apps/client` are extensionless (Metro platform resolution). Imports of
  workspace packages use their package names.
- Platform-specific code exists only in `src/adapters/**/<name>.native.ts` and
  `<name>.web.ts`, both implementing `<name>.ts` (the interface file, which also exports nothing
  platform-specific). Screens, features and services never check `Platform.OS`.
- All UI strings in `src/text/en.ts`; ids, storage keys and route paths in `src/keys/`; numbers in
  `src/constants/`.
- Components are function components with hooks; no class components.

## 1. App configuration

`apps/client/app.config.ts` (typed `ExpoConfig`):

| Key | Value |
|---|---|
| `name` / `slug` / `scheme` | `Kodakiko` / `kodakiko` / `kodakiko` |
| `version` | `0.1.0` |
| `orientation` | `portrait` |
| `userInterfaceStyle` | `automatic` |
| `newArchEnabled` | `true` |
| `android.package` | `com.x42697262.kodakiko` (D-53, pending the programmer's confirmation; changeable until the first store release) |
| `android.allowBackup` | `false` (D-12, security.md §3) |
| `android.permissions` | `[]` here; camera, microphone and location are added by spec 09 |
| `android.blockedPermissions` | `android.permission.READ_EXTERNAL_STORAGE`, `android.permission.WRITE_EXTERNAL_STORAGE` (the Photo Picker needs neither) |
| `web.output` | `single` (F-LIB-11) |
| `web.bundler` | `metro` |
| `plugins` | `expo-router`, `react-native-libsodium`, `expo-secure-store`, `expo-sqlite`, `./plugins/with-android-hardening` |
| `experiments.typedRoutes` | `true` |

`plugins/with-android-hardening.js` (config plugin, plain JS, imports `withAndroidManifest` and
`withDangerousMod` from `expo/config-plugins`, the entry point `expo` re-exports; do not import
`@expo/config-plugins` directly, it is not a direct dependency): sets `android:usesCleartextTraffic="false"` and
`android:fullBackupContent="false"` / `android:dataExtractionRules` pointing to a generated
`res/xml/data_extraction_rules.xml` that excludes every domain for both `cloud-backup` and
`device-transfer`. (Belt and braces with `allowBackup=false`, because Android 12+ device-to-device
transfer ignores `allowBackup` without extraction rules.)

The same plugin writes `res/xml/network_security_config.xml` and sets
`android:networkSecurityConfig`: `<base-config cleartextTrafficPermitted="false">` with system
trust anchors only, plus `<debug-overrides>` trusting **user** certificates. Android applies
`debug-overrides` only to debuggable builds, so the local development stack's certificate
(spec 07b §1) works in dev builds and is ignored in release builds.

The plugin also writes a **debug-only** copy, `android/app/src/debug/res/xml/network_security_config.xml`
(the debug source set overrides the main resource in debug builds only): the same content plus
`<domain-config cleartextTrafficPermitted="true">` for `localhost`, `127.0.0.1` and `10.0.2.2`.
Without it a debug build cannot load its JavaScript from Metro (`http://10.0.2.2:8081` on the
emulator, `http://localhost:8081` on a device through `adb reverse`), and the UI test stack (spec 07c,
`http://localhost:9580`) is unreachable. Release builds keep cleartext fully off. Checklist: the
release APK's merged config has no `domain-config` (inspect with `apkanalyzer resources xml`).

`react-native-libsodium` needs `minSdkVersion` ≥ 24 (F-LIB-01). React Native's own minimum has
been 24 since 0.76, so SDK 58 (React Native 0.88) already satisfies it (UNVERIFIED: read
`minSdkVersion` from the generated `android/build.gradle` after prebuild and report it; if it is
below 24, stop and ask).

Client `package.json` scripts (use these, never `pnpm exec expo …` directly, so the web worker is
always built first):

| Script | Command |
|---|---|
| `build:workers` | `node scripts/build-web-workers.ts` |
| `start:web` | `pnpm build:workers && expo start --web --port 8081` |
| `build:web` | `pnpm build:workers && expo export --platform web` |
| `prebuild:android` | `expo prebuild --platform android --clean` |
| `start:metro-ui` | `expo start --port 8081` (Metro only, for the Android UI test runner, spec 07c §4.3; the web worker is not needed) |
| `run:android` | `expo run:android` (dev build; Expo Go cannot load JSI/Nitro modules, F-LIB-01/06) |

`apps/client/README.md` documents them.

`apps/client/package.json` dependencies: every package in `docs/dependencies.md` §Client except
`expo-image-picker`, `expo-image-manipulator`, `expo-video`, `expo-video-thumbnails`,
`expo-location`, `expo-network`, `react-native-vision-camera` and its peers, `marked`, `fflate`
(added by their specs);
plus `@kodakiko/contract`, `@kodakiko/core` (`workspace:*`), `react-native-libsodium`,
`libsodium-wrappers-sumo`, `zod`. `main` = `expo-router/entry`.

## 2. Directory layout

```
apps/client/
  app/                         Expo Router routes (thin; §6)
  plugins/with-android-hardening.js
  public/workers/              build output of web/workers (gitignored, built by script)
  web/workers/argon2-worker.ts Web Worker source (§4.1)
  scripts/build-web-workers.ts esbuild bundle of web/workers → public/workers
  src/
    adapters/account-key.ts    hashAccountKey(sodium, accountKey): first 16 hex chars of genericHash(32, utf8(accountKey))
    adapters/lifecycle/        lifecycle.ts, lifecycle.native.ts, lifecycle.web.ts (§4.8)
    adapters/sodium/           sodium.ts, sodium-wrappers.ts, sodium.native.ts, sodium.web.ts
    adapters/rowstore/         rowstore.ts, rowstore.native.ts, rowstore.web.ts, schema.ts, migrations.ts
    adapters/filestore/        filestore.ts, filestore.native.ts, filestore.web.ts
    adapters/keyvault/         keyvault.ts, keyvault.native.ts, keyvault.web.ts
    adapters/session/          session-store.ts, session-store.native.ts, session-store.web.ts
    adapters/http/             http.ts (single implementation, fetch-based)
    adapters/server-url/       server-url.ts (interface + createServerUrlStore), server-url.native.ts, server-url.web.ts
    composition/               app-context.tsx, create-app-context.ts
    constants/                 client constants
    keys/                      paths.ts, storage-keys.ts, routes.ts, test-ids.ts
    text/en.ts                 UI strings
    ui/                        theme-constants.ts, theme.ts, primitives (Screen, Button, TextField, Banner, Spinner)
    features/diagnostics/      §7
```

## 3. Theme and primitives (`src/ui/`)

- `theme-constants.ts` (a constants file, so the magic-number rule exempts it): color tokens for
  light and dark (`background`, `surface`, `text`, `textMuted`, `primary`, `onPrimary`, `danger`,
  `border`, `focus`), and named maps `SPACING = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 }`,
  `RADIUS = { sm: 8, md: 12 }`, `FONT_SIZE = { caption: 13, body: 15, headline: 17, title: 22,
  largeTitle: 28 }`. Colors (`COLORS.light` / `COLORS.dark`):

  | Token | Light | Dark |
  |---|---|---|
  | `background` | `#FFFFFF` | `#0B0B0D` |
  | `surface` | `#F2F2F7` | `#1C1C1F` |
  | `text` | `#111114` | `#F2F2F5` |
  | `textMuted` | `#5C5C66` | `#A1A1AA` |
  | `primary` | `#2F5FD0` | `#7AA2FF` |
  | `onPrimary` | `#FFFFFF` | `#0B0B0D` |
  | `danger` | `#C62828` | `#FF6B6B` |
  | `border` | `#D1D1D6` | `#3A3A3F` |
  | `focus` | `#2F5FD0` | `#7AA2FF` |
- `theme.ts`: `useTheme()` picks light or dark by `useColorScheme()`.
- Primitives (each ≤ 80 lines, accessible roles and labels, a **required** `testID` prop following
  spec 07c §2; `keys/test-ids.ts` is created by this spec with `testId` and `listTestId`, 07c adds
  `TEST_IDS`):
  `Screen` (safe-area container, scroll optional), `Button` (variants `primary`, `secondary`,
  `danger`; `disabled` with `accessibilityState`; shows `Spinner` when `busy`), `TextField` (label,
  error text, `secureTextEntry` option, `autoCapitalize="none"` default), `Banner` (info/warning/
  error), `Spinner`.
- No third-party UI kit (not on the allowlist).

## 4. Adapters

### 4.1 Sodium

`sodium.ts` re-exports the `Sodium` type from core and declares
`export function loadSodium(): Promise<Sodium>`.

- `sodium.native.ts`: `import * as lib from 'react-native-libsodium'`; `await lib.ready`; map per
  `crypto.md` §2. `argon2id` wraps the synchronous `crypto_pwhash` in a `Promise` (it blocks the JS
  thread; the UI shows a static screen first, `crypto.md` §5.1 RISK-1). Errors from decrypt/open
  are rethrown as core `DecryptionError`. Before relying on `crypto_secretstream`-free functions,
  the adapter asserts at load that every needed function exists on `lib` and throws a clear error
  naming the missing one (catches wrapper regressions).
- AEAD additional data on native (D-79, F-LIB-29): the wrapper accepts only strings, so
  `sodium.native.ts` passes `utf8Decode(ad)` and first checks `bytesEqual(utf8Encode(utf8Decode(ad)),
  ad)`, throwing `MalformedPayloadError` when the bytes are not valid UTF-8 (cannot happen with core's
  AD; it guards against silent corruption). Node/web adapters pass the bytes unchanged.
- `sodium-wrappers.ts` (platform-neutral): `createWrappersSodium(lib: typeof
  import('libsodium-wrappers-sumo'), argon2id: Sodium['argon2id']): Sodium` maps every other port
  method to the wrapper library (crypto.md §2) and rethrows decrypt/open failures as
  `DecryptionError`. Node tests use it with an `argon2id` that calls `lib.crypto_pwhash` directly.
- `sodium.web.ts`: `createWrappersSodium` with `libsodium-wrappers-sumo` on the main thread for everything except `argon2id`,
  which posts `{ id, password, salt, opsLimit, memLimitBytes, outputLength }` to a dedicated
  `Worker('/workers/argon2.js')` and resolves with the output (transferring buffers, never
  copying the password into a string). One worker instance, created lazily, requests queued.
  A worker error (including out-of-memory) rejects the promise, which the adaptive loop in core
  treats as "try smaller" (`spec 04` §2.5).
- `web/workers/argon2-worker.ts`: imports `libsodium-wrappers-sumo`, awaits `ready`, handles one
  message type, posts `{ id, output }` or `{ id, error: 'argon2_failed' }`, and wipes its input
  buffers.
- `scripts/build-web-workers.ts`: esbuild bundle, `format: 'iife'`, `platform: 'browser'`,
  `minify: true`, output `public/workers/argon2.js`. Run by the `build:workers` script (§1).

### 4.2 RowStore

`rowstore.ts` defines the interface over the logical schema of `data-model.md` §4. Every method is
async. Row types live in `rowstore.ts` (`CollectionRow`, `EntryRow`, `MembershipRow`, `MediaRow`,
`ObjectRow`, `OutboxRow`, `PinRow`); binary fields are `Uint8Array`.

```ts
export interface RowStore {
  readonly open: (accountKey: string) => Promise<void>;
  readonly close: () => Promise<void>;
  readonly destroy: (accountKey: string) => Promise<void>;
  readonly transaction: <T>(work: (tx: RowStoreTx) => Promise<T>) => Promise<T>;
  readonly kvGet: (key: StorageKey) => Promise<Uint8Array | null>;
  readonly kvGetJson: <T>(key: StorageKey, schema: z.ZodType<T>) => Promise<T | null>;
  readonly kvSet: (key: StorageKey, value: Uint8Array) => Promise<void>;
  readonly kvSetJson: (key: StorageKey, value: unknown) => Promise<void>;
  readonly kvDelete: (key: StorageKey) => Promise<void>;
}
export interface RowStoreTx {
  readonly collections: TableOps<CollectionRow, string>;
  readonly entries: TableOps<EntryRow, string> & { readonly byLocalState: (states: readonly EntryLocalState[]) => Promise<readonly EntryRow[]> };
  readonly memberships: TableOps<MembershipRow, readonly [string, string]> & { readonly byEntry: (entryId: string) => Promise<readonly MembershipRow[]>; readonly byCollection: (collectionId: string) => Promise<readonly MembershipRow[]> };
  readonly media: TableOps<MediaRow, string> & { readonly byEntry: (entryId: string) => Promise<readonly MediaRow[]> };
  readonly objects: TableOps<ObjectRow, string> & { readonly byMedia: (mediaId: string) => Promise<readonly ObjectRow[]>; readonly presentOriginalsByAccess: () => Promise<readonly ObjectRow[]> };
  readonly outbox: { readonly enqueue: (row: NewOutboxRow) => Promise<number>; readonly due: (now: number, limit: number) => Promise<readonly OutboxRow[]>; readonly update: (row: OutboxRow) => Promise<void>; readonly delete: (id: number) => Promise<void>; readonly countByState: () => Promise<Readonly<Record<OutboxState, number>>>; readonly all: () => Promise<readonly OutboxRow[]> };
  readonly pins: TableOps<PinRow, string>;
}
export interface TableOps<Row, Key> {
  readonly get: (key: Key) => Promise<Row | null>;
  readonly put: (row: Row) => Promise<void>;
  readonly delete: (key: Key) => Promise<void>;
  readonly all: () => Promise<readonly Row[]>;
}
```

`OutboxRow` adds `state: 'queued' | 'dead'` to the columns of `data-model.md` §4 (`sync.md` §6's
dead-letter state, `data-model.md` §4). `accountKey` = `<serverHost>/<userId>`
so two accounts on one device never share a database.

- `rowstore.native.ts`: `expo-sqlite` `openDatabaseAsync(`kodakiko-${hash(accountKey)}.db`)`
  (hash = first 16 hex chars of `genericHash`, so file names contain no username); `PRAGMA
  journal_mode = WAL`; `PRAGMA foreign_keys = ON`; tables per `data-model.md` §4 with `BLOB`
  columns for binary data and the listed indexes; `user_version` migrations from `migrations.ts`
  (array index = version). `transaction` = `withExclusiveTransactionAsync`.
- `rowstore.web.ts`: `idb` `openDB(`kodakiko-${hash}`, version, { upgrade })` with one object store
  per table, key paths and indexes as listed; `transaction` opens one readwrite transaction over
  all stores and passes store-backed `TableOps`. IndexedDB transactions auto-commit when the
  microtask queue empties: **no `await` on non-IndexedDB promises inside `work`** (rule documented
  in `client.md` §5; services prepare data before opening the transaction).
- `schema.ts`: `ROWSTORE_SCHEMA_VERSION = 1`, table and index names (single source for both
  implementations).

### 4.3 FileStore

```ts
export interface FileStore {
  readonly open: (accountKey: string) => Promise<void>;
  readonly writeObject: (objectId: string, chunks: AsyncIterable<Uint8Array>) => Promise<number>;
  readonly readRange: (objectId: string, offset: number, length: number) => Promise<Uint8Array>;
  readonly size: (objectId: string) => Promise<number | null>;
  readonly delete: (objectId: string) => Promise<void>;
  readonly listSizes: () => Promise<ReadonlyMap<string, number>>;
  readonly destroy: (accountKey: string) => Promise<void>;
  readonly nativeUri: (objectId: string) => string | null;
  readonly readBlob: (objectId: string) => Promise<Blob | null>;
}
```

- Writes go to `<objectId>.kdks.partial` and are renamed to `<objectId>.kdks` only after the last
  chunk, so a crash never leaves a truncated object under the final name; `open` deletes leftover
  `.partial` files.
- `filestore.native.ts`: `expo-file-system` `Paths.document/kodakiko/<hash(accountKey)>/objects/`
  (`keys/paths.ts`); `File.open()` → `FileHandle` (F-LIB-05) with `offset` + `readBytes`/`writeBytes`;
  `nativeUri` returns the file URI (the upload service in spec 08 reads it).
- `readBlob`: web returns the OPFS `File` (a `Blob`; the web uploader slices it without reading it
  into memory); native returns `null` (the native uploader reads files by path).
- `filestore.web.ts`: OPFS `navigator.storage.getDirectory()` → `kodakiko/<hash>/objects/`;
  `createWritable()` for writes, `getFile()` + `slice(offset, offset + length).arrayBuffer()` for
  reads; `nativeUri` returns `null`. On first open call `navigator.storage.persist()` and store the
  result in kv (`data-model.md` §4.2).
- Temp plaintext files (`TempFiles`, spec 09 §4.1) and the Android plain thumbnail directory
  (spec 09 §4) are added by spec 09; downloads by spec 14. The memory FileStore of spec 08 and the
  diagnostics `fileStoreSelfTest` are extended by each spec that adds a method.

### 4.4 KeyVault and SessionStore

```ts
export interface KeyVault {
  readonly capabilities: () => Promise<{ readonly persistent: boolean; readonly biometric: boolean }>;
  readonly storeMasterKey: (accountKey: string, masterKey: Uint8Array) => Promise<void>;
  readonly loadMasterKey: (accountKey: string, prompt: string) => Promise<Uint8Array | null>;
  readonly forget: (accountKey: string) => Promise<void>;
}
export interface SessionStore {
  readonly save: (accountKey: string, token: string, persist: boolean) => Promise<void>;
  readonly load: (accountKey: string) => Promise<string | null>;
  readonly clear: (accountKey: string) => Promise<void>;
}
```

- `keyvault.native.ts`: `expo-secure-store` key `kodakiko.mk.<hash(accountKey)>`; value base64url;
  `requireAuthentication: true` when `expo-local-authentication` reports enrolled biometrics,
  else stored without it and `loadMasterKey` first calls `authenticateAsync({ promptMessage })`
  (device credential allowed). Invalidated item (biometrics changed) → `null`, and the item is
  deleted. `persistent: true`. The exact behavior per Android version is UNVERIFIED (crypto.md
  §9.1): this spec's checklist tests it on one device and reports.
- `keyvault.web.ts`: in-memory `Map` only in this spec (`persistent: false`, `biometric: false`);
  the opt-in WebCrypto wrap ("keep me unlocked") is spec 16.
- `session-store.native.ts`: `expo-secure-store` key `kodakiko.session.<hash>`, no
  authentication requirement.
- `session-store.web.ts`: `persist` → `localStorage`, else `sessionStorage`, key
  `kodakiko.session.<hash>`.

### 4.5 Server URL

```ts
export interface ServerUrlStore {
  readonly current: () => Promise<string | null>;
  readonly choose: (input: string) => Promise<ServerChoice>;
}
export type ServerChoice = { readonly ok: true; readonly baseUrl: string; readonly config: PublicConfigDto; readonly inviteToken: string | null } | { readonly ok: false; readonly reason: 'invalid' | 'not_https' | 'unreachable' | 'not_kodakiko' };
```

- `server-url.ts` also exports the platform-neutral logic, so it is testable in Node:
  `createServerUrlStore(deps: { fetcher: typeof fetch; storage: { get: () => Promise<string | null>;
  set: (value: string) => Promise<void> }; timeoutMs: number }): ServerUrlStore`.
- `server-url.web.ts`: `current` = `window.location.origin`; `choose` is never used on web (returns
  `{ ok: false, reason: 'invalid' }`).
- `createServerUrlStore` takes `allowLocalHttp: boolean` (native wiring passes `__DEV__`): when true,
  `http://localhost[:port]` and `http://127.0.0.1[:port]` are accepted as given (spec 07c UI tests);
  every other `http:` address, and every `http:` address when false, → `not_https`. Tested both ways.
- `server-url.native.ts` only wires `createServerUrlStore` with `fetch` and an `expo-secure-store`
  backed `storage`. Behavior of `choose` (`client.md` §7.1): parse with `URL`; accept `https://host[:port][/…]` and
  bare `host` (prefix `https://`); `http:` → `not_https`; take `#<token>` from an `/invite` path as
  `inviteToken`; fetch `GET <origin>/api/v1/config` with `HTTP_TIMEOUT_MS`; network failure →
  `unreachable`; non-2xx or schema mismatch → `not_kodakiko`; success → store `origin` in
  `expo-secure-store` key `kodakiko.server` (not secret, but it must survive and not be
  backed up).

### 4.6 Http

`http.ts`, one implementation:

```ts
export type HttpClient = {
  readonly request: <Response>(call: ApiCall<Response>) => Promise<Response>;
};
export type ApiCall<Response> = {
  readonly method: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  readonly path: string;
  readonly body?: unknown;
  readonly responseSchema: z.ZodType<Response>;
  readonly auth: 'session' | 'none' | { readonly linkToken: string };
  readonly idempotent: boolean;
  readonly timeoutMs?: number;
};
export function createHttpClient(deps: { baseUrl: () => Promise<string>; sessionToken: () => Promise<string | null>; onSessionInvalid: () => void; fetcher?: typeof fetch; sleep?: (ms: number) => Promise<void>; random?: () => number }): HttpClient;
```

- Timeout via `AbortController` (`HTTP_TIMEOUT_MS = 20_000` default).
- `idempotent: true` calls retry on network errors, `5xx` and `429` with exponential backoff and
  full jitter (`HTTP_RETRY_ATTEMPTS = 3`, base 500 ms, cap 8 s; `429` waits `Retry-After`
  seconds if present). Never retries other statuses (guidelines 7.1, `sync.md` §6).
- Non-2xx with a body matching `errorResponseSchema` → throw `ApiError { status, code, details,
  requestId }`; other non-2xx → `ApiError` with code `INTERNAL`. Network failure after retries →
  `NetworkError`. `401` with `UNAUTHENTICATED` or `SESSION_EXPIRED` also calls
  `onSessionInvalid()`.
- Success bodies are parsed with `responseSchema` (mismatch → `ApiError` code `INTERNAL`, logged
  in dev only).
- Paths come from `@kodakiko/contract` `ROUTES`. Byte conversion (`encodeBytes`/`decodeBytes`)
  happens in services, not here.

### 4.7 Clock

`src/adapters/clock.ts`: `{ now: () => Date.now(), nowIso: () => new Date().toISOString() }`;
tests inject fixed values.

`src/adapters/ids.ts`: `createRandomId(sodium): () => string` = `encodeBytes(sodium.randomBytes(16))`
(22 characters, matches `ID_PATTERN`; crypto.md §3). It is in the app context as `randomId`; every
client service that creates ids receives it.


### 4.8 Lifecycle

`lifecycle.ts`:

```ts
export interface Lifecycle {
  readonly afterInteractions: () => Promise<void>;
  readonly subscribeActive: (listener: (active: boolean) => void) => () => void;
  readonly subscribeOnline: (listener: () => void) => () => void;
}
```

React Native imports (`AppState`, `InteractionManager`) live only in `lifecycle.native.ts`; web uses
`document.visibilityState` / `visibilitychange`, `requestAnimationFrame` twice for
`afterInteractions`, and the `online` event. Services receive `Lifecycle` through the context, so
they load in plain Node tests (a fake lifecycle there).
## 5. Composition root

Adapter factories: `createRowStore({ sodium })`, `createFileStore({ sodium })`,
`createKeyVault({ sodium })`, `createSessionStore({ sodium })` (each from its `.native.ts` /
`.web.ts` file under the same exported name; they need `sodium` only for `hashAccountKey`),
`createServerUrlStore(...)`, `createHttpClient(...)`, `createLifecycle()`.

`composition/create-app-context.ts`: `export async function createAppContext(): Promise<AppContext>`
builds adapters in this order: `loadSodium`, `ServerUrlStore`, `SessionStore`, `KeyVault`,
`RowStore`, `FileStore`, `Lifecycle`, `HttpClient` (base URL from `ServerUrlStore.current`, token from the
session store for the active account), clock. Services are added by later specs to the same
object (`AppContext.services`). `composition/app-context.tsx`: `AppContextProvider` (creates the
context once, shows the `Spinner` screen while loading, an error screen with the error class name
if loading fails) and `useAppContext()`.

`AppContext` also holds an **account state** store (`composition/account-state.ts`), a minimal
observable (`subscribe`, `get`, `set`) with states `noServer` (Android only), `signedOut`,
`locked` (session present, master key not in memory), `unlocked` (with `AccountKeys` and
`userId`, in memory only), `pendingDeletion`. Spec 07b drives it.

## 6. Routes (this spec)

- `app/_layout.tsx`: wraps everything in `AppContextProvider`, theme, `SafeAreaProvider` (from
  `react-native-safe-area-context`), `Stack`.
- `app/index.tsx`: redirects by account state: `noServer`/`signedOut` → `/welcome`, `locked` →
  `/lock`, `pendingDeletion` → `/cancel-deletion`, `unlocked` → `/(tabs)`.
- `src/config/zod-config.ts`: `z.config({ jitless: true })` (zod 4 otherwise probes `new Function`
  on the first object parse, which the production CSP blocks and reports as a violation; spec 07c's
  CSP guard would fail). It is the **first** import of `app/_layout.tsx` and of every worker or
  service-worker entry that imports `zod` or `@kodakiko/contract`.
- `src/config/build-flags.ts`: `UI_TEST_MODE = process.env.EXPO_PUBLIC_UI_TEST === '1'`; when true,
  `app/_layout.tsx` calls `LogBox.ignoreAllLogs(true)` (spec 07c §4.3). On web, after `sodium.ready`,
  `document.documentElement.dataset.sodiumReady = 'true'` (07c smoke test).
- `app/(tabs)/_layout.tsx` + `app/(tabs)/index.tsx`: placeholder gallery showing `home.signedInAs` "Signed in as
  <username>" (`testID` `home.signedInAs`) and a `home.signOut` "Sign Out" button (spec 11 replaces
  the body). The `/invite` placeholder title uses key `invite.title` "Opening invite…".
- `/welcome`, `/invite`, `/sign-up`, `/sign-in`, `/recover`, `/lock`, `/cancel-deletion` are spec
  07b; this spec creates them as placeholders rendering their title only (with the title's text key
  as `testID`), so navigation compiles.
- `app/settings/diagnostics.tsx`: §7, reachable only when `__DEV__` (otherwise redirect to `/`).

## 7. Diagnostics (dev builds only)

`features/diagnostics/`: one screen with two buttons.

1. **Run crypto vectors**: `checkVectors(context.sodium, CRYPTO_VECTORS, () => performance.now())`
   and lists each case with pass/fail and duration. Summary line "N of M passed".
2. **Measure key derivation**: runs `deriveWithAdaptiveParams` with real start parameters 3 times
   (password `"diagnostics-password"`, random salt), shows min/median/max ms and the final params.

Both run on Android (dev build) and web. Results are what this spec's checklist reports.

## 8. Tests

Vitest in `apps/client` with `environment: 'node'` for adapter logic that does not need a device:

1. `http.test.ts` with a fake fetch: timeout aborts; idempotent GET retried on 503 then succeeds;
   POST not retried on 503; 429 honors `Retry-After`; error body → `ApiError` with code; 401
   `SESSION_EXPIRED` calls `onSessionInvalid`; schema mismatch → `ApiError INTERNAL`.
2. `server-url.test.ts` (`createServerUrlStore` with injected fetch and storage): `example.com` →
   `https://example.com`; `http://x` → `not_https`; invite URL → token extracted; config with wrong
   shape → `not_kodakiko`.
3. The RowStores need real SQLite and real IndexedDB, which Node tests do not have (and an
   IndexedDB fake is not on the allowlist), so they are tested on the device and in the browser
   through the diagnostics screen: a third button "RowStore self-test" runs `rowStoreSelfTest(rowStore)` (in `features/diagnostics/rowstore-self-test.ts`:
   put/get/delete for each table, a transaction that throws rolls back, outbox `due` ordering),
   on both platforms. A fourth button "FileStore self-test" runs `fileStoreSelfTest(fileStore)`
   (`features/diagnostics/filestore-self-test.ts`): write a 3-chunk object from an async iterable,
   `size`, `readRange` at the start, middle and end, `listSizes`, `readBlob` (web non-null, native
   null), a write that throws midway leaves no final file and the next `open` removes the
   `.partial`, `delete`, `destroy`.
4. Core-level logic (none new here).

## 8a. Implementation answers (2026-11-12)

1. RN 0.88 removed `InteractionManager`: `lifecycle.native.ts` uses `requestIdleCallback`.
2. `newArchEnabled` is omitted from `app.config.ts` (not in the SDK 58 type; New Architecture is
   always on).
3. Accepted: the `./server-url.ts` explicit import plus the zero-argument overload;
   `HTTP_RETRY_ATTEMPTS = 3` total attempts; server timestamps as ISO strings, local ones as epoch
   milliseconds; `AccountState.unlocked` carries `username`; `AppContext.activeAccountKey`;
   `onSessionInvalid` → `signedOut`; native `subscribeOnline` is a no-op; web `storage.persist()`
   not awaited; a failed write deletes its `.partial` immediately.
4. `react-native-libsodium: true` is in `allowBuilds` (recorded in dependencies.md).

## 9. Steps

1. App config, plugin, package deps; `expo prebuild --platform android`; report `minSdkVersion`.
2. Theme, text, keys, primitives.
3. Adapters in §4 order; worker build script.
4. Composition root, account state, routes, diagnostics.
5. Unit tests; `pnpm verify`.
6. Android dev build on a real mid-range device; web in Chrome on a laptop.

## 10. Verification checklist

- [ ] `pnpm verify` green.
- [ ] Android dev build installs and opens to `/welcome` placeholder; web opens the same.
- [ ] Diagnostics on Android: vectors N of N passed (paste summary). Same on web.
- [ ] Diagnostics key derivation: Android min/median/max and final params; web the same (device
      and browser model named). **RISK-1:** if any median > 8000 ms, stop and report to Opus
      before spec 07b.
- [ ] RowStore self-test passes on both platforms.
- [ ] KeyVault on Android: store, kill app, load with biometric prompt → same bytes; enroll a new
      fingerprint → load returns `null` (report the Android version; UNVERIFIED behavior from
      crypto.md §9.1 confirmed or not).
- [ ] `adb shell dumpsys package com.x42697262.kodakiko | grep -i backup` shows backup disabled;
      `adb backup` (if available on the device's Android version) yields no app data.
- [ ] Generated `AndroidManifest.xml` contains `usesCleartextTraffic="false"` and the data
      extraction rules.
- [ ] No `Platform.OS` outside `src/adapters/` (`grep -rn "Platform.OS" apps/client/src apps/client/app`).

## 11. Done when

Checklist fully reported. RISK-1 numbers reported to the programmer.
