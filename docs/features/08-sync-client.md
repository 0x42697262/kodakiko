# 08 — Sync client: decisions in core, driver, uploads, native upload module

Status: draft 1 (2026-11-11).

Goal: the client side of `docs/design/sync.md`: pure decision functions and the metadata merge in
core; the key-resolution service; the sync driver (push, pull, triggers, dead letters); the
uploader port with the web implementation and the Android native foreground-service module; an
in-memory RowStore/FileStore for tests; and an end-to-end test package that runs client sync code
against a real server, Postgres and Garage. Entry creation UI is spec 10; this spec ships the
outbox **executors** for entry kinds so spec 10 only enqueues.

Read first: `docs/design/sync.md` (all); `docs/design/storage.md` §3; `docs/design/api.md`
§§5-8; spec 07a conventions; facts F-AND-01. Depends on specs 06 and 07a.

## 1. Contract and constants

`packages/contract/src/outbox.ts` (shared because the e2e tests and the client build payloads):

```ts
export const OUTBOX_KINDS = ['createEntry', 'updateEntryMeta', 'addMedia', 'removeMedia', 'trashEntry', 'restoreEntry', 'deleteEntry'] as const;
export const entryOutboxPayloadSchemas = { createEntry: z.object({ entryId: idSchema }), updateEntryMeta: z.object({ entryId: idSchema }), addMedia: z.object({ entryId: idSchema, mediaId: idSchema }), removeMedia: z.object({ entryId: idSchema, mediaId: idSchema }), trashEntry: z.object({ entryId: idSchema }), restoreEntry: z.object({ entryId: idSchema }), deleteEntry: z.object({ entryId: idSchema }) } as const;
```

Sharing and public-link actions are **not** outbox kinds: they need the network anyway (key lookup,
security-code check, link registration) and run as direct online calls (D-64, specs 17-18).

Payloads carry ids only; everything else is read from the RowStore when the item runs, so an item
always pushes the **latest** local state (an edit made after enqueueing is not lost, and two edits
collapse into one push).

`packages/core/src/sync/constants.ts`: `PUSH_CONCURRENCY = 4`, `MAX_ATTEMPTS = 8`,
`BACKOFF_BASE_MS = 2000`, `BACKOFF_CAP_MS = 900_000`, `PULL_PAGE_LIMIT = 500`,
`SYNC_INTERVAL_MS = 60_000`, `SYNC_DEBOUNCE_MS = 2000`, `UPLOAD_PART_CONCURRENCY = 3`.

## 2. Core (`packages/core/src/sync/`)

### 2.1 `plan.ts`

```ts
export type OutboxItemView = { readonly id: number; readonly kind: OutboxKind; readonly entryId: string | null; readonly state: 'queued' | 'dead'; readonly nextAttemptAt: number };
export function planPushBatch(items: readonly OutboxItemView[], inFlightEntryIds: ReadonlySet<string>, now: number, capacity: number): readonly number[];
```

Rules: consider `queued` items with `nextAttemptAt ≤ now` in ascending `id`; skip an item when an
earlier item (any state, including `dead` and not-yet-due) has the same `entryId`, or its entry is
in flight (same-entry order, `sync.md` §3); return at most `capacity` ids.

### 2.2 `failures.ts`

```ts
export type PushFailure = { readonly kind: 'network' } | { readonly kind: 'http'; readonly status: number; readonly code: ErrorCode; readonly retryAfterSeconds: number | null };
export type FailureDecision = { readonly action: 'retry'; readonly at: number } | { readonly action: 'conflict' } | { readonly action: 'drop' } | { readonly action: 'dead' } | { readonly action: 'signedOut' };
export function classifyPushFailure(kind: OutboxKind, failure: PushFailure, attempts: number, now: number, random: () => number): FailureDecision;
export function nextAttemptAt(attempts: number, now: number, random: () => number): number;
```

| Failure | Decision |
|---|---|
| network, 5xx, `STORAGE_UNAVAILABLE` | `retry` at `nextAttemptAt`, or `dead` once `attempts ≥ MAX_ATTEMPTS` |
| 429 | `retry` at `now + retryAfter` (or backoff when absent); counts as an attempt |
| `REVISION_CONFLICT` | `conflict` |
| 404 `NOT_FOUND` for kinds `updateEntryMeta`, `trashEntry`, `restoreEntry`, `removeMedia`, `deleteEntry` | `drop` (entry gone on the server, `sync.md` §5: no resurrection) |
| 404 `NOT_FOUND` for `createEntry`, `addMedia` and any other kind | `dead` (a collection or entry it depends on is gone; the user sees it) |
| 401 | `signedOut` |
| 403 `QUOTA_EXCEEDED`, `FEATURE_DISABLED`, 413, 422, other 4xx | `dead` immediately (retrying cannot help; the user sees it) |

`nextAttemptAt` = `now + random() × min(BACKOFF_CAP_MS, BACKOFF_BASE_MS × 2^attempts)` (full
jitter).

### 2.3 `collections-plan.ts`

```ts
export type RemoteCollectionState = { readonly id: string; readonly kind: CollectionKind; readonly role: CollectionRole; readonly lastSeq: number; readonly tombstonesPrunedThroughSeq: number; readonly metaUpdatedAt: string };
export type LocalCollectionState = { readonly id: string; readonly cursorSeq: number; readonly metaUpdatedAt: string };
export type CollectionsPlan = { readonly refetch: readonly string[]; readonly pull: readonly string[]; readonly resync: readonly string[]; readonly drop: readonly string[] };
export function planCollections(remote: readonly RemoteCollectionState[], local: readonly LocalCollectionState[]): CollectionsPlan;
```

- Remote collections of `kind = 'link'` are ignored entirely (link copies are never pulled, spec 18
  §2.1).
- `drop`: local ids absent remotely (deleted or unshared).
- `refetch`: remote ids absent locally, or remote `metaUpdatedAt` ≠ local `metaUpdatedAt`.
- `resync`: present locally with `cursorSeq > 0 && cursorSeq < tombstonesPrunedThroughSeq` (the
  same condition as the server's `needsResync`, spec 06 §3; a cursor of 0 never resyncs).
- `pull`: present (or about to be refetched) with `lastSeq > cursorSeq`, excluding `resync` ids
  (resync implies a pull from 0).

`LocalCollectionState.metaUpdatedAt` is the RowStore column `collections.metaUpdatedAt`
(`data-model.md` §4; RowStore schema version stays 1 because no RowStore has shipped).

### 2.4 `pulled-entry.ts`

```ts
export type LocalEntryState = { readonly localState: EntryLocalState; readonly baseRevision: number } | null;
```

An entry created on this device and not yet confirmed (`pending_create`) has `baseRevision = 0`
(spec 10 writes it that way), so any pulled revision (≥ 1) gives `updateTrashOnly` while it is
pending.

```ts
export type PulledEntryDecision = 'insert' | 'overwrite' | 'updateTrashOnly' | 'rejectRollback';
export function decidePulledEntry(local: LocalEntryState, pulledRevision: number): PulledEntryDecision;
```

Exactly `sync.md` §4.1 step 3: absent → `insert`; `synced` and `pulled ≥ base` → `overwrite`;
pending local change and `pulled ≥ base` → `updateTrashOnly` (the pulled blob is kept as
`serverPendingBlob`, see §3.3); `pulled < base` → `rejectRollback`.

### 2.5 `merge.ts`

```ts
export function mergeMetadata(ancestor: EntryMetadataV1, local: EntryMetadataV1, server: EntryMetadataV1, nowUtc: string): EntryMetadataV1;
export function metadataEquivalent(left: EntryMetadataV1, right: EntryMetadataV1): boolean;
```

Field rules from `sync.md` §5, applied in this order:

| Field | Rule |
|---|---|
| `note` | unchanged on one side (vs ancestor) → the other side; changed on both → the side with the later `editedAtUtc`; equal times → local |
| `tags` | `(ancestor − removedLocal − removedServer) ∪ addedLocal ∪ addedServer`, where added/removed are set differences against the ancestor; result normalized with `normalizeTag` (created by this spec in `packages/core/src/domain/tags.ts`, rules of `data-model.md` §6.2, because merge needs it; spec 10 reuses it), deduplicated, sorted |
| `takenAtUtc` + `utcOffsetMinutes` + `timeZone` (one unit) | as `note` |
| `location` | as `note` |
| `media` | ids removed on either side stay removed; order = local order of the remaining ids, then server-only additions in server order; descriptors taken from whichever side has the id (server wins when both have it and they differ) |
| `createdAtUtc` | ancestor's |
| `editedAtUtc` | `nowUtc` |
| `schemaVersion` | 1 |

`metadataEquivalent` compares every field except `editedAtUtc` after tag normalization; arrays in
order; `location` numbers exactly.

## 3. Client

### 3.1 Test support (`apps/client/test/support/`)

`memory-rowstore.ts` (`createMemoryRowStore(): RowStore`) and `memory-filestore.ts`, implementing
the 07a interfaces with `Map`s, including `transaction` semantics (copy-on-write, discard on
throw). Used by client unit tests and by `tests/e2e`. They are exported from the client package
via `package.json` `exports["./test-support"]` (test-only entry; never imported by `src/`).

### 3.2 Key resolution (`src/services/keys/entry-keys.ts`)

```ts
export type EntryKeys = {
  readonly collectionKey: (collectionId: string) => Promise<Uint8Array>;
  readonly entryKey: (entryId: string) => Promise<Uint8Array>;
  readonly mediaKey: (entryId: string, mediaId: string) => Promise<Uint8Array>;
  readonly variantKey: (entryId: string, mediaId: string, variant: 'original' | 'thumbnail') => Promise<Uint8Array>;
  readonly clear: () => void;
};
export function createEntryKeys(deps: { sodium: Sodium; rowStore: RowStore; account: () => AccountKeys | null }): EntryKeys;
```

- Collection key: owner → `unwrapKey(masterKey, keyMaterial, collectionKeyByMaster, collectionId)`;
  viewer → `openSharePayload(sodium, keyMaterial, publicKey, privateKey, collectionId)`; `link`
  collections → `kdfDerive` from the unwrapped link secret (`linkSecretByMaster`, spec 18 uses it).
- Entry key: the first membership of the entry whose collection key resolves (home first) →
  `unwrapKey(collectionKey, wrap, entryKeyByCollection, `${collectionId}/${entryId}`)`.
- Media key: `unwrapKey(entryKey, mediaKeyByEntry, mediaKeyByEntry, `${entryId}/${mediaId}`)`.
- Variant key: `kdfDerive(32, variant === 'original' ? KDF_SUBKEY_IDS.mediaOriginal :
  KDF_SUBKEY_IDS.mediaThumbnail, KDF_CONTEXTS.media, mediaKey)` (`crypto.md` §4); not cached (cheap);
  callers `wipe` it after use. Specs 11, 13, 14, 15, 18 and 20 obtain variant keys only through this.
- In-memory `Map` caches; `clear()` wipes every cached key; called on lock/sign-out (account
  state transitions from 07a). Locked account → `AccountLockedError`.

### 3.3 RowStore columns used here (listed in `data-model.md` §4)

- `collections.metaUpdatedAt` (string).
- `entries.serverPendingBlob` (bytes | null) and `entries.serverPendingRevision` (number | null):
  the newest server version pulled while a local change was pending (`updateTrashOnly`); the push
  conflict path uses it to avoid one extra round trip.
- `objects.uploadState`: `'local' | 'queued' | 'uploading' | 'uploaded'` plus `uploadJobId`
  (string | null).

### 3.4 Uploader port (`src/adapters/uploader/`)

```ts
export type UploadJob = { readonly objectId: string; readonly parts: readonly { readonly partNumber: number; readonly url: string; readonly offset: number; readonly length: number }[] };
export type UploadEvent = { readonly type: 'progress'; readonly objectId: string; readonly sentBytes: number } | { readonly type: 'completed'; readonly objectId: string } | { readonly type: 'failed'; readonly objectId: string; readonly reason: 'expired' | 'network' | 'http' | 'missingFile' } | { readonly type: 'stopped'; readonly reason: 'timeout' | 'cancelled' };
export interface Uploader {
  readonly enqueue: (jobs: readonly UploadJob[]) => Promise<void>;
  readonly statuses: () => Promise<ReadonlyMap<string, 'queued' | 'uploading' | 'completed' | 'failed'>>;
  readonly subscribe: (listener: (event: UploadEvent) => void) => () => void;
  readonly cancelAll: () => Promise<void>;
}
```

A single PUT is one part with `partNumber: 0`, `offset: 0`, `length: size`.

- `uploader.web.ts`: queue in memory; at most `UPLOAD_PART_CONCURRENCY` parts in flight; each part
  `fetch(url, { method: 'PUT', body: blob.slice(offset, offset + length), headers: {
  'Content-Type': 'application/octet-stream' } })` where `blob = await fileStore.readBlob(objectId)`
  (07a §4.3; the OPFS `File`, no full read into memory; `createWebUploader({ fileStore })`); `AbortController` timeout `UPLOAD_PART_TIMEOUT_MS = 300_000`; network errors retried
  3 times with backoff; 403 → `failed: expired`; other non-2xx → `failed: http`. `statuses` from
  the in-memory map (a page reload loses it; the driver then confirms or re-presigns, §3.6).
- `uploader.native.ts`: thin wrapper over `packages/native-upload` (§4), passing
  `FileStore.nativeUri(objectId)` as the file path.

### 3.5 Outbox executors (`src/services/sync/executors/`)

One file per kind, each `(item, deps) => Promise<ExecutorResult>`; `ExecutorResult` = `'done' |
'waiting' | { failure: PushFailure }`. `'waiting'` keeps the item queued with `nextAttemptAt = now +
SYNC_INTERVAL_MS` without counting an attempt. Every executor reads current rows inside a RowStore transaction, then
does network calls **outside** it (07a IndexedDB rule), then writes results in a new transaction.

| Kind | Work |
|---|---|
| `createEntry` | 1 `POST /entries` with the **current** local blob, memberships and media rows (200 or 201 both fine); in the same step record that posted blob as `baseMetadataBlob` with `baseRevision = 1` (the server now holds exactly that); 2 for every object of the entry's media with `uploadState = 'local'`: enqueue an upload (§3.6), set `queued`; 3 when every object is `uploaded`: `localState = 'synced'` if `metadataBlob` equals `baseMetadataBlob` byte for byte, else `'pending_update'` (an edit made after the POST; its own queued `updateEntryMeta` pushes it) → `done`; otherwise `'waiting'`. A repeat run after step 1 succeeded skips the POST when `baseRevision ≥ 1` |
| `updateEntryMeta` | runs only after the entry's `createEntry` item is gone (same-entry order); if `metadataBlob` equals `baseMetadataBlob` byte for byte → `done` without a request; else `PATCH` with the current local blob and `baseRevision`; success → base copies = the **posted** blob and the new revision; then if the local `metadataBlob` still equals the posted blob byte for byte → `synced` and `done`; else (the user edited while the PATCH was in flight) `pending_update` and the same item is re-run immediately (`nextAttemptAt = now`, not counted as an attempt); `REVISION_CONFLICT` → §3.7 |
| `addMedia` | `POST /entries/{id}/media` then uploads as in `createEntry` step 2-3 |
| `removeMedia` | `DELETE /media/{id}`; 404 counts as done |
| `trashEntry` / `restoreEntry` | the POST; done |
| `deleteEntry` | `DELETE /entries/{id}`; then delete local rows and files |

### 3.6 Upload coordination (`src/services/sync/uploads.ts`)

- `enqueueObjectUploads(objectIds)`: for each object: `POST /objects/presign` (idempotent by id,
  spec 05) → build `UploadJob` (multipart parts: `offset = (partNumber − 1) × partSizeBytes`) →
  `Uploader.enqueue` → `uploadState = 'uploading'`.
- Before enqueueing, `uploadPolicy.allowed(sizeBytes)` (spec 14 §7; a dependency that always
  returns `true` until spec 14) — not allowed → stay `queued`, retried on network change or the next
  pass.
- Uploader events (`progress`, `completed`, `failed`) for object ids that have no RowStore
  `objects` row are ignored here (spec 18's link uploads use the same uploader and handle their
  own ids).
- On `completed`: `POST /objects/{id}/confirm` → `uploaded`; for a **thumbnail** object then call
  `thumbnailCache.afterUpload(objectId)` (spec 14 §5; a no-op dependency until spec 14). On `UPLOAD_INCOMPLETE`: multipart →
  re-enqueue the missing parts from `GET /objects/{id}/parts` (it returns `partSizeBytes`, so no
  local column is needed); single PUT → call `presign` again (same id, re-issues the URL) and
  re-enqueue. On `failed: expired`: presign again (fresh URLs). On `failed:
  missingFile`: object row `dead` + outbox item `dead` (local data loss; surfaced). On `stopped:
  timeout`: nothing; the next foreground pass resumes.
- After every change of an object's `uploadState`, emit `entriesChanged([entryId])` (the gallery's
  "not synced" dot, spec 11).
- `reconcileUploads()` on every sync pass: objects in `uploading` whose uploader status is
  `completed` or unknown (app restarted) → try confirm; `UPLOAD_INCOMPLETE` → resume with parts.
  This is the recovery path for events missed while JS was suspended.

### 3.7 Conflict path (`src/services/sync/conflict.ts`)

On `REVISION_CONFLICT` with `details { revision, metadataBlob }` (or a stored
`serverPendingBlob`): resolve the entry key; open ancestor (`baseMetadataBlob`), local
(`metadataBlob`), server (details) with `openJson(EntryMetadataV1, …)`; `merged =
mergeMetadata(...)`; if `metadataEquivalent(merged, server)` → adopt server blob and revision,
`synced`, done (`sync.md` §5 step 4); else seal `merged`, store it as the local blob with
`baseRevision = revision` and `baseMetadataBlob = server blob`, retry the PATCH immediately once
(a second conflict goes back through backoff).

### 3.8 Pull (`src/services/sync/pull.ts`)

1. `GET /sync/state` → `planCollections` with local collection rows.
2. `drop`: delete the collection, its memberships, and entries left without memberships (with
   their media, objects, files).
3. `refetch`: `GET /collections/{id}` → upsert row (`kind`, `role`, `ownerId`, `ownerUsername`,
   `keyMaterial`, `metadataBlob`, `metaUpdatedAt`).
4. For each `pull`/`resync` collection: loop `GET …/diff?since=cursor&limit=PULL_PAGE_LIMIT`
   (`resync` starts at 0 after deleting that collection's memberships locally); apply the page in
   **one** transaction: membership upserts/removals, `decidePulledEntry` per entry, media rows,
   object rows (`uploadState = 'uploaded'`, `localState` as is or `missing`); cascades exactly as
   `sync.md` §4.1: a removed membership that leaves an entry with no membership deletes the entry,
   its media rows and object rows; an object row replaced by a newer row for the same media
   variant is deleted; the files of every deleted object row (and plain thumbnails of deleted
   media) are collected and deleted **after** the transaction commits; then set
   `cursorSeq = nextCursor` in the same transaction; `resyncRequired` mid-loop → restart that
   collection from 0.
5. Emit `entriesChanged(entryIds)` on the context's event bus (`src/composition/events.ts`, a tiny
   typed emitter created in this spec) after each page; spec 11's index subscribes.

### 3.9 Driver (`src/services/sync/sync-service.ts`)

```ts
export type SyncService = {
  readonly start: () => void;
  readonly stop: () => void;
  readonly syncNow: () => Promise<SyncReport>;
  readonly onPassCompleted: (listener: (report: SyncReport) => void) => () => void;
  readonly notifyLocalWrite: () => void;
  readonly status: () => SyncStatus;
  readonly subscribe: (listener: (status: SyncStatus) => void) => () => void;
  readonly retryDead: () => Promise<void>;
};
export type SyncStatus = { readonly running: boolean; readonly queued: number; readonly dead: number; readonly waitingForWifi: number; readonly lastSuccessAt: number | null; readonly offline: boolean };
```

- `start` (called on transition to `unlocked`): subscribe to `AppState` (`active` → pass), web
  `online` → pass, interval `SYNC_INTERVAL_MS` while active, uploader events; immediately one pass.
  `stop` (on lock/sign-out) unsubscribes and cancels timers; in-flight HTTP calls finish, their
  results are written.
- A pass = `reconcileUploads` → push drain (repeat `planPushBatch` until nothing is due, running
  up to `PUSH_CONCURRENCY` executors) → pull. One pass at a time; triggers during a pass set a
  "run again" flag (`sync.md` §8). `notifyLocalWrite` debounces `SYNC_DEBOUNCE_MS`.
- Failures: `classifyPushFailure`; `retry` → `attempts + 1`, `nextAttemptAt`; `dead` → state
  `dead`, `lastErrorCode`; `drop` → delete item (and for update kinds mark the entry as deleted
  locally if the server says it is gone); `signedOut` → stop, account state `signedOut` via the
  07a `onSessionInvalid` path.
- `offline` = the last request failed with `NetworkError`; cleared on any success.
- `retryDead` resets dead items to queued, `attempts = 0`.

### 3.10 Status UI

`src/features/sync/sync-status-banner.tsx`, first matching branch wins: offline → `Banner` info
"Offline. Changes will sync when you're connected."; dead > 0 → warning "(n) =>
\`Couldn't sync ${n} ${n === 1 ? 'item' : 'items'}.\`" with a "Retry" button (`retryDead`);
`waitingForWifi > 0` and nothing else queued → info "(n) => \`Waiting for Wi-Fi to upload ${n}
large ${n === 1 ? 'item' : 'items'}.\`" (spec 14 §7; `waitingForWifi` = objects held back by the
upload policy, 0 until spec 14); otherwise hidden. `SyncReport` = `{ pushed, pulledEntries,
failed, durationMs }`; `onPassCompleted` fires after every pass that reached the server. Placed on
the placeholder gallery (spec 11 keeps it). Strings in `text/en.ts` under `sync.*`.

## 4. `packages/native-upload` (Expo module, Android only)

Created as a local Expo module package (`expo-module.config.json` with `platforms: ["android"]`,
`android.modules: ["dev.kodakiko.nativeupload.NativeUploadModule"]`), consumed by `apps/client`
as `workspace:*`; autolinking picks it up. Kotlin only; no new third-party dependency (OkHttp ships
with React Native; use the app's OkHttp, do not declare a new version). The package's
`package.json` has `peerDependencies: { "expo": "*" }`; its TypeScript imports
`requireNativeModule` from `expo` (Expo re-exports the modules API there), and its Kotlin code uses
the `expo.modules.kotlin` API that autolinking provides.

JS API (`src/index.ts`):

```ts
export function enqueue(jobs: readonly NativeUploadJob[]): Promise<void>;
export function statuses(): Promise<Record<string, 'queued' | 'uploading' | 'completed' | 'failed'>>;
export function cancelAll(): Promise<void>;
export function addListener(listener: (event: NativeUploadEvent) => void): { remove(): void };
export function requestNotificationPermission(): Promise<boolean>;
export type NativeUploadJob = { objectId: string; filePath: string; parts: { partNumber: number; url: string; offset: number; length: number }[] };
```

`requestNotificationPermission` uses `ActivityCompat.requestPermissions` for
`POST_NOTIFICATIONS` on Android 13+ (returns `true` immediately below 13) and resolves with the
result; no extra package (`expo-notifications` is not allowlisted).

Kotlin:

- `NativeUploadModule` (Expo `Module` DSL): functions above; events `onUploadEvent`; on
  `enqueue` it adds jobs to the in-process queue and starts `UploadService` with
  `ContextCompat.startForegroundService`.
- `UploadService : Service`: `startForeground(NOTIFICATION_ID, notification,
  ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`; notification channel `uploads` (low
  importance), title "Uploading to your journal", text "(n) items left", determinate progress by
  bytes; worker coroutine (`Dispatchers.IO`) uploads parts sequentially per object, up to
  `UPLOAD_PART_CONCURRENCY` objects in parallel; each part: `RandomAccessFile` seek to `offset`,
  stream exactly `length` bytes as an OkHttp `RequestBody` with fixed `contentLength`,
  `PUT` with `Content-Type: application/octet-stream`, call/read timeouts 300 s; network errors
  retried 3 times (2 s, 8 s, 30 s); HTTP 403 → `failed expired`; other non-2xx → `failed http`;
  missing file → `failed missingFile`. Queue empty → `stopSelf()`.
- `onTimeout(startId, fgsType)` (API 35, F-AND-01): stop workers, emit `stopped timeout`,
  `stopSelf()` immediately.
- Statuses kept in a process-wide `ConcurrentHashMap` so `statuses()` answers after JS resumes.
  The queue is not persisted across process death; JS `reconcileUploads` re-enqueues.
- Manifest (module's `AndroidManifest.xml`, merged by Gradle): `<service
  android:name=".UploadService" android:foregroundServiceType="dataSync" android:exported="false"/>`,
  permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.
- Notification permission (Android 13+): the client calls `requestNotificationPermission()` once,
  the first time an upload is enqueued, after showing a `Banner` with "Allow notifications to see
  upload progress." Denial does not block uploads; the answer is stored in kv so it is asked once.
- Strings in `res/values/strings.xml` (Kotlin has no comments either; the no-comments lint rule does
  not cover Kotlin, the rule still applies by review).

## 5. End-to-end test package (`tests/e2e`)

New workspace package `@kodakiko/e2e` (`private`), added to `pnpm-workspace.yaml` packages
(`tests/*`); depends on `@kodakiko/server` (`./testing`), `@kodakiko/client` (`./test-support` and
`./testing`), where the client's `src/testing.ts` (created by 07b with `createTestAuthContext`) also
exports
`createTestClientContext({ baseUrl, fetcher, sodium, clock }): Promise<TestClientContext>` building
the same services as `createAppContext` but with memory RowStore/FileStore, an in-memory session
store and key vault, a fake lifecycle, and the web uploader over the memory FileStore; it is never
imported by `src/` code, `@kodakiko/core`, `@kodakiko/contract`, `libsodium-wrappers-sumo`,
`vitest`. It is the only place where server and client code meet (architecture rule: `apps/client`
never imports `apps/server`; a test package may import both).

Harness: Postgres + Garage from the test compose file (spec 05); `buildApp` + `listen({ port: 0 })`
with `REQUIRE_HTTPS=false`, `S3_PUBLIC_ENDPOINT = S3_ENDPOINT`; two simulated devices = two client
contexts (memory RowStore/FileStore, wrappers Sodium, real `fetch` Http, web uploader against the
in-memory FileStore through a `Blob` built from its bytes) for the same account.

Helper `createEntryLocally(device, { mediaBytes: Uint8Array[], note, tags })`: does what spec 10's
service will do (keys, STREAM-encrypt into the FileStore, seal metadata, rows, outbox
`createEntry`) using only core functions.

Scenarios:
1. Device A creates an entry with 2 media (one 10 MiB, multipart) → `syncNow` → device B
   `syncNow` → B has the entry, media and uploaded object rows; B downloads one object, decrypts
   with core `createStreamDecryptor`, bytes equal.
2. Both edit the note offline (different fields: A changes the note, B adds a tag) → A syncs, B
   syncs (conflict) → both end with A's note and B's tag; revision 3 on both after another pull.
3. Replay: A's PATCH response is dropped (fake fetch wrapper drops the response once) → the item
   completes through the equivalent-merge path without a second revision bump.
4. Trash on A, edit on B → after sync: trashed on both, edit applied (`sync.md` §5).
5. Delete on A, edit on B → B's update item is dropped, entry gone on B.
6. Quota: tiny quota → `createEntry` item ends `dead` with `QUOTA_EXCEEDED`; status `dead = 1`;
   raise quota; `retryDead` → synced.
7. Rollback guard: by SQL, set the entry's `revision` and `metadata_blob` back to an older pair
   **and** allocate a new sequence number for its membership (`UPDATE collections SET last_seq =
   last_seq + 1 … RETURNING`, then set the membership `seq`), so B's pull actually receives the row
   → B logs `sync.rollback_rejected` and keeps its newer blob.
8. Resync: prune tombstones with a moved clock → B (old cursor) resyncs and ends identical to A.

## 6. Data model

`data-model.md` §4 already lists the columns of §3.3. If the implementation needs any column not
listed there, stop and ask; do not edit the design doc.

## 7. Steps

1. Contract outbox + core sync modules + `normalizeTag` + unit tests (table-driven merge tests:
   at least 25 cases covering every rule row).
2. Client memory adapters; entry keys; uploader web; executors; uploads; conflict; pull; driver;
   events; banner; unit tests with memory adapters and fake Http.
3. `packages/native-upload` + client native uploader.
4. `tests/e2e` package + scenarios.
5. Android manual run (§8).

## 8. Verification checklist

- [ ] `pnpm verify` green; `pnpm --filter @kodakiko/e2e test` green with all 8 scenarios (paste
      summary).
- [ ] Android dev build: enqueue a 200 MB object (test button in diagnostics that writes random
      ciphertext-sized bytes to the FileStore and calls `enqueueObjectUploads`), press Home →
      notification shows progress → upload completes in the background; reopen → confirm happens
      (`uploaded`).
- [ ] Airplane mode mid-upload → service retries, reports `failed network` after retries; reopen
      with network → `reconcileUploads` resumes from missing parts (server log shows `parts` call).
- [ ] Force-stop the app mid-upload → reopen → upload resumes and completes.
- [ ] `adb shell dumpsys activity services com.x42697262.kodakiko` shows the service with type
      `dataSync` while uploading.
- [ ] `onTimeout` path: simulate with `adb shell cmd activity` FGS timeout debug command if the
      device supports it; if the command is not available, a Kotlin unit test (Robolectric is not
      allowlisted, so a plain JVM test of the extracted `TimeoutHandler` class) proves that
      `onTimeout` stops the workers, emits `stopped timeout` and calls `stopSelf()`; report which
      path was used.
- [ ] No plaintext or key bytes in any log line (grep debug logs for a known key's base64url).
