# 06 — Entries server: entries, media, memberships, sequence numbers, diff

Status: draft 1 (2026-11-11).

Goal: the server's data plane for journal entries: create/read/patch/trash/restore/delete, media
rows, per-collection sequence numbers, tombstones, the sync diff and sync state,
read access for collections (owner or share recipient), collection list/read/rename, and the jobs
`purge-trash`, `erase-accounts`, `prune-tombstones`. Implements the `onObjectUploaded` hook left
open in spec 05. Creating shared or link collections, shares and adding entries to shared
collections are spec 17/18; this spec builds the machinery they reuse.

Read first: `docs/design/data-model.md` §§2-3, 5; `docs/design/api.md` §§5-8;
`docs/design/sync.md` §§4-6; spec 03 conventions. Depends on specs 04 and 05.

## 1. Contract

| File | Content |
|---|---|
| `collections.ts` | `COLLECTION_KINDS = ['home', 'shared', 'link'] as const`, `COLLECTION_ROLES = ['owner', 'viewer'] as const`, `collectionDto` (api §5), `collectionListResponse { items, nextCursor }`, `collectionPatchRequest { metadataBlob }` |
| `entries.ts` | `ENTRY_PURPOSES = ['journal', 'link_copy'] as const`; `entryDto`; `entryCreateRequest { id, purpose, metadataBlob, memberships: [{ collectionId, entryKeyByCollection }] (min 1, max 10), media: [{ mediaId, mediaKeyByEntry }] (min 1, max MEDIA_PER_ENTRY_HARD_MAX) }`; `entryPatchRequest { metadataBlob, baseRevision }`; `revisionConflictDetails { revision, metadataBlob }` |
| `media.ts` | `mediaDto`, `mediaCreateRequest { mediaId, mediaKeyByEntry }` |
| `sync.ts` | `diffQuery { since, limit }` (coerced from query strings), `diffPage` (api §8), `syncStateResponse` (api §8) |
| `routes.ts` | `collections()`, `collection(id)`, `entries()`, `entry(id)`, `entryTrash(id)`, `entryRestore(id)`, `entryMedia(id)`, `media(id)`, `collectionDiff(id)`, `syncState()` |

Schema maxima (10 memberships) are hard shape limits; the configured limit
(`maxMediaPerEntry`) is checked by the service and return `VALIDATION_FAILED`
with a detail naming the limit.

## 2. Settings additions

| Variable | Type | Default | Path |
|---|---|---|---|
| `MAX_ENTRY_METADATA_BYTES` | int ≥ 1024 | 524288 | `limits.maxEntryMetadataBytes` (sealed blob bytes after base64url decoding; a 100,000-character note in 4-byte UTF-8 plus padding fits) |
| `MAX_DIFF_PAGE_SIZE` | int 10-2000 | 500 | `sync.maxDiffPageSize` |
| `TRASH_DAYS` | int ≥ 1 | 30 | `entries.trashDays` |
| `TOMBSTONE_RETENTION_DAYS` | int ≥ 7 | 90 | `sync.tombstoneRetentionDays` |
| `JOB_PURGE_TRASH_INTERVAL_MINUTES` | int ≥ 1 | 60 | `jobs.purgeTrashIntervalMs` (minutes × 60,000) |
| `JOB_ERASE_ACCOUNTS_INTERVAL_MINUTES` | int ≥ 1 | 60 | `jobs.eraseAccountsIntervalMs` |
| `JOB_PRUNE_TOMBSTONES_INTERVAL_MINUTES` | int ≥ 1 | 1440 | `jobs.pruneTombstonesIntervalMs` |

(`MAX_COLLECTION_METADATA_BYTES` comes from spec 04.) Constant `COLLECTIONS_PAGE_MAX = 200` and
`COLLECTIONS_PAGE_DEFAULT = 100` in `config/constants.ts` for `GET /collections`.


## 3. Domain (pure)

`domain/entries.ts`:

```ts
export type MembershipTarget = { readonly collectionId: string; readonly kind: CollectionKind; readonly ownerId: string };
export function validateCreateMemberships(input: { userId: string; purpose: EntryPurpose; targets: readonly MembershipTarget[]; flags: Flags }): MembershipDecision;
export function canWriteEntry(userId: string, entryOwnerId: string): boolean;
export function isIdenticalCreate(existing: ExistingEntrySnapshot, request: CreateSnapshot): boolean;
export function trashCutoff(now: Date, trashDays: number): Date;
export function tombstoneCutoff(now: Date, retentionDays: number): Date;
```

`validateCreateMemberships` returns `{ ok: true } | { ok: false, code: ErrorCode }`:
1. every target owned by `userId`, else `NOT_FOUND`;
2. duplicate collection ids → `VALIDATION_FAILED`;
3. `journal`: exactly one target of kind `home`; other targets only kind `shared` and only when
   `flags.sharing` (else `FEATURE_DISABLED`); kind `link` → `VALIDATION_FAILED`;
4. `link_copy`: exactly one target, kind `link`, `flags.publicLinks` (else `FEATURE_DISABLED`).

`isIdenticalCreate`: same owner, purpose, byte-equal metadata blob, same set of collection ids,
same set of media ids.

`domain/sync.ts`:

```ts
export function sortedForLocking(collectionIds: readonly string[]): readonly string[];
export function mergeDiffRows(memberships: readonly SeqRow[], tombstones: readonly SeqRow[], limit: number): { readonly rows: readonly SeqRow[]; readonly hasMore: boolean };
export function nextCursor(page: { rows: readonly SeqRow[]; hasMore: boolean }, since: number, collectionLastSeq: number): number;
export function needsResync(since: number, prunedThroughSeq: number): boolean;
```

- `sortedForLocking`: unique, ascending by plain string comparison (`<`), the deadlock-avoidance
  order of `data-model.md` §3.
- `mergeDiffRows`: both inputs ascending by `seq` (each fetched with `limit + 1`); merge, take the
  first `limit`; `hasMore` when more than `limit` rows existed in total.
- `nextCursor`: `hasMore` → seq of the last returned row; otherwise `collectionLastSeq` (safe
  because the diff reads in one repeatable-read snapshot, §5; it lets clients skip gaps left by
  rows whose seq moved forward).
- `needsResync`: `since > 0 && since < prunedThroughSeq`. A client starting from 0 never needs a
  resync: it receives the current memberships, and tombstones only matter to clients that already
  hold rows. (Without the `since > 0` term, a client restarting from 0 after a resync would be told
  to resync again, forever.)

## 4. Repositories

`adapters/db/sequence-repository.ts`:

| Method | SQL |
|---|---|
| `allocate(trx, collectionIds, now)` | for each id in `sortedForLocking` order, one statement `UPDATE collections SET last_seq = last_seq + 1, updated_at = $now WHERE id = $id RETURNING last_seq`; returns `Map<collectionId, seq>`. Must run inside a transaction |

`updated_at` changes with every allocation; `meta_updated_at` (in the initial schema,
`data-model.md` §2.4) does not. Collection PATCH sets it; `CollectionDto.metaUpdatedAt` and
`/sync/state` `metaUpdatedAt` read it.

`adapters/db/entries-repository.ts`:

| Method | Notes |
|---|---|
| `insertEntry(trx, row)` | unique violation on id → `IdConflict` |
| `findEntry(trx, id, { forUpdate })` | |
| `findEntrySnapshot(trx, id)` | entry + membership collection ids + media ids (for `isIdenticalCreate`) |
| `updateMetadata(trx, id, blob, revision, now)` | |
| `setTrashedAt(trx, id, value, now)` | |
| `deleteEntry(trx, id)` | cascades memberships and media (FKs) |
| `insertMemberships(trx, rows)` | |
| `membershipCollectionIds(trx, entryId)` | |
| `updateMembershipSeqs(trx, entryId, seqByCollection)` | one `UPDATE … FROM (VALUES …)` |
| `insertTombstones(trx, rows)` | |
| `deleteMembership(trx, collectionId, entryId)` | |
| `insertMedia(trx, row)`, `findMedia(trx, id)`, `countMedia(trx, entryId)`, `deleteMedia(trx, id)` | |
| `selectTrashedBefore(cutoff, limit)` | ids |
| `findOwnedIds(trx, userId, ids)` | ids among `ids` owned by the user |

`adapters/db/collections-repository.ts` (extend from spec 04): `findById`, `findReadable(userId,
id)` (joined with the owner's `username` → `ownerUsername`) (owner → role `owner` with `keyByOwner`; recipient → role `viewer` with the
`sealed_collection_key`; else null), `listReadable(userId, afterId, limit)` (ordered by id; also `ownerUsername`;
excludes `link` collections of other users — they never have shares anyway), `updateMetadata(id,
blob, now)`, `syncState(userId)`.

`adapters/db/diff-repository.ts`:

| Method | SQL |
|---|---|
| `membershipsSince(trx, collectionId, since, limit)` | `entry_memberships` `seq > since` ordered by `seq` limit `limit + 1`, joined with `entries.trashed_at` |
| `tombstonesSince(trx, collectionId, since, limit)` | `membership_tombstones` `seq > since` ordered, limit `limit + 1` |
| `entriesByIds`, `mediaByEntryIds`, `uploadedObjectsByMediaIds` | `WHERE … = ANY($1)` batch reads (no N+1) |

## 5. Services

`services/sequence-service.ts`: `bumpEntry(trx, entryId, now)` = `membershipCollectionIds` →
`allocate` → `updateMembershipSeqs`. Used by every entry change and by the spec 05 hook.

`services/entries-service.ts` (`createEntriesService({ db, settings, clock, objectsRepository,
sequenceService })`; `objectsRepository` is spec 05's repository, whose methods take the
transaction first). Every
write runs in one transaction. Check order per guidelines 3.2.

| Method | Steps |
|---|---|
| `create(userId, request)` | 1 decoded blob ≤ `maxEntryMetadataBytes` (`PAYLOAD_TOO_LARGE`); media count ≤ `maxMediaPerEntry`; 2 load targets, `validateCreateMemberships`; 3 transaction: existing id → `isIdenticalCreate` ? return existing with `created: false` : `ID_CONFLICT`; insert entry (revision 1); `allocate` for all target collections; insert memberships with those seqs; insert media rows (media id already used → `ID_CONFLICT`); 4 log `entry.created` (userId, entryId, purpose, mediaCount, collectionCount). Controller maps `created: false` to `200` |
| `get(userId, id)` | readable through any membership in a collection the user owns or receives → `EntryDto`; else `NOT_FOUND`. Viewers do not see trashed entries (`NOT_FOUND`) |
| `patch(userId, id, { metadataBlob, baseRevision })` | size check; transaction: `findEntry forUpdate`; missing or not owner → `NOT_FOUND`; `baseRevision !== revision` → `AppError('REVISION_CONFLICT', { revision, metadataBlob })`; update with `revision + 1`; `bumpEntry` |
| `trash(userId, id)` / `restore(userId, id)` | owner; already in the target state → return without a bump; else set/clear `trashed_at`, `bumpEntry` |
| `remove(userId, id)` | owner; absent → success (idempotent); transaction: `allocate` for every membership collection, insert one tombstone per membership with its new seq, `objectsRepository.markDeletingByMediaIds(trx, mediaIds, deleteAfter(now, objectGraceDays))`, `deleteEntry`. Log `entry.deleted`. The transaction body is the **exported** function `removeEntryInTransaction(trx, entryId, now, storage)` (spec 18's link cleanup calls it inside its own transaction); `remove` checks ownership first, `removeAsSystem(entryId)` (used by `purge-trash`) skips that check |
| `addMedia(userId, entryId, { mediaId, mediaKeyByEntry })` | owner; existing same media on the same entry with the same wrap → return it (`created: false`); other → `ID_CONFLICT`; `countMedia < maxMediaPerEntry` else `VALIDATION_FAILED`; insert; bump |
| `removeMedia(userId, mediaId)` | owner of its entry else `NOT_FOUND`; objects of the media → `deleting`; delete media row; bump. The client also patches the entry metadata (separate outbox item, `sync.md` §2.1) |
| `onObjectUploaded(trx, entryId)` | `bumpEntry`; wired into spec 05's `confirm`, which calls it **inside** `markUploaded`'s transaction (spec 05 §6 defines that signature) |

`services/sync-service.ts`:

| Method | Steps |
|---|---|
| `diff(userId, collectionId, since, limit)` | 1 `limit` defaults to `min(500, maxDiffPageSize)` and is **clamped** to `maxDiffPageSize` (never rejected); 2 transaction `ISOLATION LEVEL REPEATABLE READ, READ ONLY`; 3 `findReadable` else `NOT_FOUND`; 4 `needsResync` → `{ resyncRequired: true, memberships: [], entries: [], media: [], objects: [], nextCursor: 0, hasMore: false }`; 5 fetch memberships and tombstones, `mergeDiffRows`; 6 for role `viewer`, memberships whose entry is trashed become `{ removed: true, entryKeyByCollection: null }` (recipients never receive trashed content; restore bumps the seq and brings it back); 7 batch-load entries, media, uploaded objects of the non-removed rows; 8 `nextCursor` with the collection's `last_seq` read in the same transaction |
| `state(userId)` | `syncState`: every readable collection with `{ id, kind, role, lastSeq, tombstonesPrunedThroughSeq, metaUpdatedAt: meta_updated_at }` |

`services/collections-service.ts`: `list(userId, cursor, limit)` (cursor = base64url of the last
id; `limit` default `COLLECTIONS_PAGE_DEFAULT`, clamped to `COLLECTIONS_PAGE_MAX`), `get(userId, id)`, `rename(userId, id, metadataBlob)` (owner, size ≤
`maxCollectionMetadataBytes`, sets `meta_updated_at`).

## 6. Routes

`http/routes/entries.ts` (group `entries`), `media.ts` (group `media`), `collections.ts` and
`sync.ts` (group `collections`), all `requireSession`:

`POST /api/v1/entries`, `GET/PATCH/DELETE /api/v1/entries/:id`, `POST /api/v1/entries/:id/trash`,
`POST /api/v1/entries/:id/restore`, `POST /api/v1/entries/:id/media`, `DELETE /api/v1/media/:id`, `GET /api/v1/collections`,
`GET/PATCH /api/v1/collections/:id`, `GET /api/v1/collections/:id/diff`, `GET /api/v1/sync/state`.

## 7. Jobs

| Job | Lock id | Run |
|---|---|---|
| `purge-trash` | 1201 | `selectTrashedBefore(trashCutoff, JOB_BATCH_SIZE)`; for each id: `entriesService.removeAsSystem(id, { onlyIfTrashedBefore: trashCutoff })`; log `entry.purged` with count |
| `erase-accounts` | 1202 | users `pending_deletion` with `deletion_due_at < now`, up to `JOB_BATCH_SIZE`; per user, step 1: delete all their sessions; all their objects not yet `deleting` → `deleting` with `delete_after = now`; step 2: when the user has zero object rows left (GC from spec 05 removes them), delete the user row (cascades collections, entries, memberships, media, shares, sessions, invites they created become `created_by = null`). Log `account.erased` at step 2 |
| `prune-tombstones` | 1203 | per collection with tombstones older than `tombstoneCutoff`: in one transaction delete them (up to `JOB_BATCH_SIZE` rows per run) and set `tombstones_pruned_through_seq = greatest(current, max deleted seq)` |


### Implementation answers (2026-11-12)

1. `removeEntryInTransaction(trx, entryId, now, storage)` where `storage: Pick<StorageSettings,
   'objectGraceDays' | 'uploadUrlTtlSeconds'>`; objects of removed media get `delete_after = now +
   max(objectGraceDays days, uploadUrlTtlSeconds)` (the same rule as spec 05 §10c item 3, via one
   shared helper `objectDeleteAfter(now, storage)` in `domain/objects.ts`). `entries-service`,
   `purge-trash` and spec 18 pass `settings.storage`.
2. Every new repository method takes the executor (`Kysely<Database>` or `Transaction<Database>`)
   first, as in spec 05 (`insertHome` unchanged).
3. Query schemas: `since` required int ≥ 0; `limit` optional int ≥ 1, values above the maximum are
   clamped; a malformed `GET /collections` cursor → `422 VALIDATION_FAILED`; helper schemas
   `collectionIdParams`, `entryIdParams`, `mediaIdParams`, `collectionListQuery` in the contract.
4. `removeAsSystem(id, { onlyIfTrashedBefore })`: inside its transaction it re-reads the entry `FOR
   UPDATE` and does nothing unless `trashed_at` is set and ≤ `onlyIfTrashedBefore` (an entry
   restored between the job's select and the delete is kept). Test: restore between select and
   remove → entry survives.

## 8. Tests

Unit: `validateCreateMemberships` (every rule and flag combination), `isIdenticalCreate`,
`sortedForLocking`, `mergeDiffRows` (interleaved seqs, limit boundaries, `hasMore`), `nextCursor`,
`needsResync`.

Integration (Postgres; use real routes and the spec 04 test-account helper; blobs are random bytes
since the server never opens them):

1. Create: `201`; same request again → `200` identical body; same id with a different blob →
   `409 ID_CONFLICT`; journal without a home membership → `422`; `link_copy` with flags off → `403
   FEATURE_DISABLED`; membership in another user's collection → `404`; 21 media with max 20 →
   `422`; oversize blob → `413`.
2. Sequence numbers: create two entries → home `last_seq` 2, memberships seq 1 and 2; patch the
   first → its seq becomes 3; diff since 0 returns entry 2 (seq 2) then entry 1 (seq 3) in that
   order, `nextCursor 3`.
3. Concurrency: 20 parallel creates into the same home collection → `last_seq = 20`, seqs 1..20
   unique.
4. Deadlock avoidance: an entry in collections A and B and another in B and A (test inserts the
   second membership via the repository); 50 parallel patches across both entries → no deadlock
   error, all succeed.
5. Patch conflict: patch with `baseRevision 1` twice → second `409 REVISION_CONFLICT` with
   `details.revision 2` and the stored blob.
6. Trash/restore: trash twice → seq bumped once; diff shows `trashedAt`; restore clears it.
7. Delete: tombstone with a new seq; diff since before returns `removed: true`; objects of its media
   moved to `deleting`; delete again → `204`.
8. Foreign entry: trash, restore, patch and delete of another user's entry → `404` each, and the
   entry is unchanged.
9. Media: add → bump; add the same media again → `200`; remove → objects `deleting`, bump.
10. Object hook: confirm an upload (spec 05 route) → the entry's membership seq increases and the
    diff includes the object row; a pending object is never in a diff.
11. Pagination: 7 changes, `limit 3` → pages of 3, 3, 1; `hasMore` true, true, false; union equals
    a single `limit 500` diff.
12. Snapshot cursor: when the last page is empty because rows moved forward, `nextCursor` equals
    `last_seq`.
13. Resync: prune with a fixed clock → `tombstones_pruned_through_seq` raised; diff with `0 < since
    < pruned` → `resyncRequired: true`; diff with `since = 0` → normal page with the current rows
    and `resyncRequired: false`.
14. Viewer view (test helper `insertSharedCollectionFixture(db, { ownerId, recipientId })` with raw
    Kysely inserts, because the repositories for shares arrive in spec 17): trashed entry appears
    as `removed: true`; `GET /entries/:id` for it → `404`; owner still sees it.
15. Sync state: lists home with `role owner`; after a rename `metaUpdatedAt` changes; after an
    entry patch `metaUpdatedAt` does **not** change.
16. Erase: request deletion (spec 04), move the clock past the due date, run `erase-accounts` →
    objects `deleting`; run `collect-objects` (spec 05) then `erase-accounts` again → user row gone,
    username free for a new sign-up.
17. Purge: trashed 31 days ago → gone after `purge-trash`; 29 days → kept.

## 9. Steps

1. Contract. 2. Domain + unit tests. 3. Repositories, sequence service, entries/sync/collections
services, hook wiring, routes. 4. Jobs. 5. Integration tests. 6. `pnpm verify` + integration suite.

## 10. Verification checklist

- [ ] Suites green (paste summaries); tests 3 and 4 passed 10 runs in a row.
- [ ] `EXPLAIN` of the diff membership query uses `entry_memberships_feed_idx` (paste the plan line).
- [ ] No N+1: with query logging on, one diff page of 50 entries issues a constant number of
      queries (paste the count; expected ≤ 8).

## 11. Done when

Checklist fully reported; suites green.
