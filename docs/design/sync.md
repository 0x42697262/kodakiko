# Sync

Status: draft 1 (2026-11-11). Normative for `packages/core/src/sync/` (pure state machine),
`apps/client/src/services/sync/` (driver), and the server's sync endpoints in
`docs/design/api.md`.

## 1. Model

Offline-first, single-writer per entry, pull-by-cursor (D-06, D-42).

- The **client** owns its data: it creates entries, media and objects with client-generated ids
  (D-43) and pushes them to the server.
- The **server** is a shared, append-only record of ciphertext rows plus per-collection sequence
  numbers. It is the authority for membership, sharing and sequence order, never for content.
- A device is **one of several writers** of the same entries (the same user on phone and web).
  Different users never write the same entry (sharing is read-only, D-39), so cross-user write
  conflicts do not exist. Same-user cross-device conflicts are resolved by revision (section 5).

Two directions, both driven by one engine:

| Direction | Trigger | Carries |
|---|---|---|
| Push (drain the outbox) | after a local write, on app foreground, on network-connect, every `SYNC_INTERVAL_MS` while online | new entries, metadata edits, media add/remove, object uploads, trash/restore, deletes (sharing is a direct call, D-64) |
| Pull (fetch diffs) | after a successful push batch, on app foreground, on network-connect, every `SYNC_INTERVAL_MS` while online | changed and removed memberships per collection, since each collection's cursor |

## 2. Local state that drives sync

From `docs/design/data-model.md` section 4:

- `outbox`: ordered work items. Each has `kind`, a JSON `payload` (ids and ciphertext references
  only), `attempts`, `nextAttemptAt`, `lastErrorCode`.
- `entries.localState`: `synced`, `pending_create`, `pending_update` (metadata only; pending trash,
  restore and delete are tracked by their outbox items alone, spec 10).
- `entries.baseMetadataBlob` + `baseRevision`: the last version the server confirmed. Used for the
  three-way merge in section 5.
- `collections.cursorSeq`: the highest sequence number this device has applied for that collection.
- `objects.localState` / `uploadState`: cache presence vs. upload progress (`uploaded` = confirmed on
  the server).

### 2.1 Outbox items

```ts
export const OutboxKind = z.enum([
  'createEntry',
  'updateEntryMeta',
  'addMedia',
  'removeMedia',
  'trashEntry',
  'restoreEntry',
  'deleteEntry',
]);
```

| Kind | Meaning |
|---|---|
| `createEntry` | create entry row + memberships + media rows, then upload objects |
| `updateEntryMeta` | metadata edit (note, tags, time, location, media reorder) |
| `addMedia` | new media row + object uploads for one entry |
| `removeMedia` | drop media row + objects -> deleting |
| `trashEntry` | set trashed_at |
| `restoreEntry` | clear trashed_at |
| `deleteEntry` | permanent local+server delete (from trash or multi-select) |

Sharing and public-link actions are direct online calls, not outbox items (D-64).

One outbox item is the smallest retryable unit. Idempotency: every server call carries the
client-generated id, so a replay is a no-op (section 6).

## 3. Push: ordering and grouping

The driver processes the outbox **in insertion order** but groups items that must go together:

1. A `createEntry` item also enqueues the object uploads for its media, in order, as one logical
   group: the entry row (with its memberships and media rows, one request) is created first, then
   each object is uploaded and confirmed. The item completes when every object is confirmed; a
   failed upload is retried on its own (the entry create is idempotent).
2. Two items for the same entry (`updateEntryMeta` after `createEntry`) must apply in order; the
   driver never reorders same-entry items.
3. Items for different entries may be pushed concurrently, bounded by
   `constants.PUSH_CONCURRENCY` (default 4).

### 3.1 Object upload within a push

For each object in a `createEntry` / `addMedia` group:

1. `POST /objects/presign`: the server checks size and quota, reserves the bytes in `used_bytes`
   and returns single-PUT or multipart URLs (`docs/design/storage.md` §3).
2. Client uploads the ciphertext object straight to the bucket. Uploads use the Android
   foreground service when the app is backgrounded (D-33) or the web in-page queue.
3. `POST /objects/{id}/confirm`: the server verifies (and for multipart completes) the object,
   flips it to `uploaded`, and bumps the membership `seq` so other devices pull it.

An interrupted single PUT restarts; an interrupted multipart upload resumes with
`GET /objects/{id}/parts`. Expired URLs are re-requested with the same calls.

## 4. Pull: cursors and diffs

For each collection the device can read (its home collection and collections shared with it; `link`
collections are ignored, spec 18 §2.1):

1. Client sends `GET /collections/{id}/diff?since={cursorSeq}&limit={n}`.
2. Server returns memberships with `seq > cursorSeq`, ascending, up to `limit` (hard max
   `settings.sync.maxDiffPageSize`), plus tombstones with `seq > cursorSeq`, plus `hasMore` and the
   new highest `seq` seen (`nextCursor`).
3. Client applies each change (upsert membership + entry ciphertext row, or mark removed), then
   sets `cursorSeq = nextCursor` **only after** the whole page is committed locally.
4. If `hasMore`, repeat. If the server reports `resyncRequired` (the cursor is below
   `tombstones_pruned_through_seq`, data-model §2.8), the client drops the collection's local rows
   and re-pulls from `since = 0`.

Pull is read-only on the server. It never advances a cursor the client has not durably applied, so
an interrupted pull replays the same page safely.

### 4.1 Applying a pulled entry

For each membership in the diff:

1. `removed: true`: delete the local membership; if the entry has no membership left, delete the
   entry, its media, its objects and its cached files.
2. Otherwise upsert the membership row (`entryKeyByCollection`, `seq`).
3. Entry row, by case:
   - absent locally: insert with `localState = 'synced'`, `baseRevision = revision`,
     `baseMetadataBlob = metadataBlob`;
   - present and `localState = 'synced'`: if `pulled.revision >= local.baseRevision`, overwrite
     blob, revision and base copies; always overwrite `trashedAt` (trash does not change revision);
   - present with a pending local change: store only `trashedAt` and keep the pulled blob as the
     next merge input (section 5); the pending push resolves it;
   - `pulled.revision < local.baseRevision`: **reject** the pulled blob and log
     `sync.rollback_rejected` (a server must never move an entry backwards; `security.md` §1.2).
4. Upsert the page's `media` rows and `objects` rows (`uploadState = 'uploaded'`). New objects
   start `localState = 'missing'`; the cache fills on demand (gallery thumbnails first, originals
   on open or pin). An object row replaced by a newer one for the same media variant is deleted
   locally with its file.
5. Update the in-memory index for every changed entry (decrypt once, `client.md` §3.1).

## 5. Conflict resolution (same user, two devices)

Only metadata edits conflict (object uploads are immutable and additive; media add/remove are
commutative). Rule: **last-writer-wins per field, with a whole-entry revision guard.**

When a `updateEntryMeta` push arrives with `baseRevision = r` but the server row is at revision
`r' > r`:

1. The server does **not** apply it. It returns `409 conflict` with the current server
   `revision` and `metadata_blob` (ciphertext).
2. The client decrypts both the server blob and its own pending edit against `baseMetadataBlob`
   (the common ancestor it still holds) and merges field by field:
   - `note`: if only one side changed it from the ancestor, take that side; if both changed it,
     take the side with the later `editedAtUtc` (clock skew is tolerated because both edits are
     already on the same wall clock source, the user's devices; a tie goes to the local edit).
   - `tags`: union of both sides' additions minus the union of both sides' removals relative to
     the ancestor, re-normalized and de-duplicated (data-model §6.2).
   - `takenAtUtc` / `utcOffsetMinutes` / `location`: last edit by `editedAtUtc`, tie to local.
   - `media` order and membership: union; an item removed on either side stays removed; order is
     the local order with server-only additions appended.
3. The merged metadata gets `revision = r' + 1`, `baseRevision = r'`, a fresh `editedAtUtc`, and
   is re-pushed. The `baseMetadataBlob` becomes the server's `r'` blob.
4. If the merged metadata equals the server's decrypted metadata (compared field by field after
   normalization, ignoring `editedAtUtc`), nothing is pushed: the client adopts the server blob
   and revision and marks the entry `synced`. This is also how a replayed edit whose first
   response was lost completes.

Trash, restore and delete are unconditional on the server and do not change `revision`. A
pending metadata edit for an entry that is gone on the server (`404`) is dropped (no
resurrection); an edit to a trashed entry is still applied (the user may restore it later).

All merge functions are pure and live in `packages/core/src/sync/merge.ts`; they take
`(ancestor, local, server, nowUtc)` and return the merged metadata. Unit-tested with many cases
(guidelines 12), including both-changed, one-changed, tag add/remove overlap, and media
add/remove overlap.

## 6. Idempotency and retries

| Operation | Idempotent because | Retry policy |
|---|---|---|
| `createEntry` (server row) | client id is the primary key; a second create with the same id and identical ciphertext returns the existing row (`200`, not `201`) | safe to retry |
| object single PUT | the object is immutable and keyed by client id; re-PUT writes the same bytes | safe to retry |
| object multipart | `uploadId` + part numbers; a resumed upload reuses them | safe to retry |
| `POST /objects/{id}/confirm` | `HeadObject` (single) or `ListParts` + complete (multipart); a repeat finds the object `uploaded` and returns it unchanged | safe to retry |
| `updateEntryMeta` | guarded by `baseRevision`; a replay after a lost response gets `409` with the server blob, which equals the local edit; the merge then produces no change and the item completes | safe (merge handles it) |
| `trashEntry` / `restoreEntry` | unconditional state set | safe to retry |
| share / unshare (direct call, spec 17) | keyed by `(collection_id, recipient_id)`; an identical repeat of a share returns `200` with the existing share (a different sealed key for an existing recipient is `409 ALREADY_SHARED`); re-unshare is a no-op | safe to retry |
| `deleteEntry` | deleting an absent entry returns `204` | safe to retry |

Retries use exponential backoff with full jitter and a cap (`nextAttemptAt` in
`packages/core/src/sync/failures.ts`, parameters in `packages/core/src/sync/constants.ts`): attempt
`n` waits `random × min(cap, base × 2^n)`. The full decision table is spec 08 §2.2. **Never retried**
(guidelines 7.1): `401`, `403`, `404`, `409` (handled by merge, not retried blindly), `422`, and
any denial. A `429` waits for the `Retry-After` header, then retries.

An outbox item that fails `constants.MAX_ATTEMPTS` times (default 8) moves to a `dead` sub-state:
it stops auto-retrying, is surfaced in the UI as "Couldn't sync N items" (text catalog), and can
be retried manually. This is the dead-letter path (guidelines 6).

## 7. Pure decisions, impure driver

`packages/core/src/sync/` has no I/O. Instead of one generic state machine it exports small pure
decision functions, each unit-tested on its own; exact signatures are in
`docs/features/08-sync-client.md` §2:

| Function | Decides |
|---|---|
| `planPushBatch` | which due outbox items run now (insertion order, same-entry items strictly sequential, at most `PUSH_CONCURRENCY` at once) |
| `classifyPushFailure` | retry with backoff, wait for `Retry-After`, merge on conflict, drop, or dead-letter |
| `nextAttemptAt` | exponential backoff with full jitter and a cap |
| `planCollections` | from `/sync/state` and local rows: which collections to refetch, pull, resync or drop |
| `decidePulledEntry` | the §4.1 cases for one pulled entry (insert, overwrite, keep pending, reject rollback) |
| `mergeMetadata` / `metadataEquivalent` | the §5 merge and the "nothing to push" check |

The client driver (`apps/client/src/services/sync/`) owns the adapters: it reads rows from the
RowStore, calls these functions, performs the I/O through `Http`, `Uploader` and `FileStore`, and
writes the results back. Decisions are tested with fixed inputs; the driver is tested end to end
against a real server in the `tests/e2e` package.

## 8. Triggers and throttling

| Trigger | What runs |
|---|---|
| App foreground; web `online` event | one push-drain then pull pass (Android has no network-change trigger in v1; the periodic pass covers reconnects) |
| Local write (create/edit/trash) | enqueue outbox item; if online and not already running, schedule a drain (`SYNC_DEBOUNCE_MS`, default 2 s) |
| Periodic while foregrounded and online | every `SYNC_INTERVAL_MS` (default 60 s) |
| Android background | object uploads always run in the foreground upload service (D-33, `storage.md` §3.3), which keeps going in the background until its queue is empty or the Android 15 daily budget ends (F-AND-01). Confirms, metadata edits and trash wait for the next foreground (the server bumps sequence numbers only at confirm, so other devices see the media after this device opens the app again). No WorkManager in v1 |
| Manual "Sync now" (settings) | immediate push+pull, shows progress |

Only one push-drain and one pull pass run at a time per direction, guarded by an in-memory lock in
the driver. A second trigger while running is coalesced into a "run again when done" flag.

## 9. Failure and partial state

- A crash mid-push leaves outbox items un-confirmed; the next drain re-runs them (idempotent).
- A crash mid-pull leaves `cursorSeq` un-advanced; the next pull re-fetches the same page.
- An object uploaded but not confirmed is reconciled: on the next `createEntry`/`addMedia` retry the
  client re-confirms; the server's `HeadObject` makes confirmation idempotent.
- The server's `abort-stale-uploads` job (data-model §5) cleans `pending` objects the client never
  confirmed, so a half-uploaded object never counts against quota forever.
- Network loss mid-upload: the Android service and web queue mark the object `nextAttemptAt` with
  backoff; no data is lost because the ciphertext is already in the local FileStore.

## 10. What is explicitly not synced in v1

- Device settings (`kv.settings.device`) are local per device.
- The in-memory index and thumbnails are derived, rebuilt per device.
- Account-level changes (password, recovery) happen on one device and take effect server-side
  immediately; other devices re-authenticate on their next request (a revoked session forces
  re-sign-in).
