# 17 — Sharing with other users (flag `sharing`, read-only)

Status: draft 1 (2026-11-11).

Goal: an owner creates a named **shared journal**, adds entries to it, and shares it read-only with
other users on the same server (D-04, D-39). Recipients see it under "Shared", can browse, view and
download its entries, and can leave it; the owner can remove entries, remove people, rename and
delete it. All of it end-to-end encrypted: the server sees membership rows, sealed keys and
ciphertext only. Online-only actions (D-64).

Read first: `docs/design/crypto.md` §§4, 8.1; `docs/design/api.md` §§4, 5, 5.1; `docs/design/data-model.md`
§§2.4-2.7; `docs/design/security.md` §1 (key substitution residual risk); specs 06 (sequence
service, entries service, collections service), 08 (entry keys, pull, drop), 10 (bulk sheet), 11
(index scopes), 12 (`EntryGrid`), 13 (viewer "Share…"), 16 (lock steps); decisions D-04, D-31,
D-39, D-64. Depends on 10-13 and 16.

## 1. Server

### 1.1 Settings

| Variable | Type | Default | Path |
|---|---|---|---|
| `MAX_RECIPIENTS_PER_COLLECTION` | int 1-500 | 50 | `sharing.maxRecipientsPerCollection` |
| `MAX_SHARED_COLLECTIONS_PER_USER` | int 1-10000 | 200 | `sharing.maxSharedCollectionsPerUser` (owned `shared` collections) |

### 1.2 Contract additions

- `collections.ts`: `collectionCreateRequest { id, kind: z.enum(['shared', 'link']), keyByOwner, metadataBlob }`; `collectionDto` gains `recipientCount: number | null` (owners of `shared` collections only, else `null`);
  `collectionEntryAddRequest { entryId, entryKeyByCollection }`; `shareCreateRequest {
  recipientUserId, sealedCollectionKey }`; `shareDto { recipientUserId, recipientUsername,
  createdAt }`; `shareListResponse { items }`.
- `users.ts`: `publicKeyResponse { userId, username, publicKey }`.
- Routes (both `ROUTE_PATTERNS` and `ROUTES`): `collectionEntries(id)`, `collectionEntry(id,
  entryId)`, `collectionShares(id)`, `collectionShare(id, recipientUserId)`,
  `userPublicKey(username)`.

### 1.3 Repositories

`collections-repository.ts` gains: `insertShared(trx, row)`, `deleteCollection(trx, id)`,
`countOwnedShared(userId)`. New `shares-repository.ts`: `insert(trx, row)` (unique violation →
`AlreadyShared`), `find(collectionId, recipientId)`, `list(collectionId)` (joined with usernames),
`delete(trx, collectionId, recipientId)`, `count(collectionId)`. `users-repository.ts` gains
`findPublicKeyByUsername(username)`. (`ownerUsername` in `CollectionDto` already comes from spec 06's
`findReadable`/`listReadable`.) `collections-repository.ts` also gains `recipientCounts(ownerId)`
(map collection id → share count) for the owner's `recipientCount`.

### 1.4 Service (`services/sharing-service.ts`)

Flag rules: methods on `shared` collections and shares check `settings.flags.sharing`; the
`kind = link` branches of `createCollection` and `deleteCollection` check `settings.flags.publicLinks`
instead (else `FEATURE_DISABLED`), so public links work with sharing turned off. Order of checks per
guidelines 3.2; an identical repeat is detected **before** any limit check (api.md §1 idempotency).

| Method | Steps |
|---|---|
| `createCollection(userId, request)` | existing id: identical row → return it (`200`), different → `ID_CONFLICT`; `kind = shared`: flag `sharing`, `keyByOwner` = 73 bytes, metadata ≤ `maxCollectionMetadataBytes`, `countOwnedShared < maxSharedCollectionsPerUser` (else `VALIDATION_FAILED` with `details.limit`), `insertShared`; `kind = link`: flag `publicLinks`, same byte checks, no count limit (spec 18 limits links), `insertLink` (same row shape, `kind 'link'`); both set `meta_updated_at = now` |
| `deleteCollection(userId, id)` | owner; `kind` `home` → `FORBIDDEN`; `kind` `link` → delegate to spec 18's `deleteLinkCollection` (removes its copies; until spec 18 is built, `FORBIDDEN`); `shared` → delete (cascades memberships and shares; entries stay in the owner's home collection) |
| `addEntry(userId, collectionId, { entryId, entryKeyByCollection })` | collection owned by user and `kind = shared`, else `NOT_FOUND`; entry owned by user, `purpose = journal`, not trashed, else `NOT_FOUND`; wrap = 73 bytes; existing membership with the identical wrap → `200`; different wrap → `ID_CONFLICT`; transaction: `allocate(trx, [collectionId], now)`, insert membership with that seq |
| `removeEntry(userId, collectionId, entryId)` | owner; `kind = shared`; membership absent → `204`; transaction: allocate, delete membership, insert tombstone with the new seq |
| `share(userId, collectionId, { recipientUserId, sealedCollectionKey })` | owner, `kind = shared`; recipient exists, `status = active`, not the owner (else `VALIDATION_FAILED`); `count < maxRecipientsPerCollection`; sealed key length = `crypto_box_SEALBYTES (48) + payload length` where payload length = `1 + 1 + 22 + 32` → exactly 104 bytes (`SEALED_SHARE_BYTES` constant), else `VALIDATION_FAILED`; existing share with an identical key → `200`; different → `409 ALREADY_SHARED`; insert; log `share.created` (owner, collection, recipient ids); set `meta_updated_at = now` on the collection is **not** needed (recipients learn from `/sync/state`, where the collection appears) |
| `listShares(userId, collectionId)` | owner only |
| `unshare(actorId, collectionId, recipientUserId)` | actor is the owner or `actorId === recipientUserId`; else `NOT_FOUND`; delete (absent → `204`) |
| `publicKey(username)` | user with that exact username and `status = active` → `{ userId, username, publicKey }`; else `NOT_FOUND`. No fingerprint: the server has no crypto library (architecture §4.1), and only a fingerprint computed by the client is worth trusting |

Unknown username → `NOT_FOUND` (rate group `users`).

Viewer reads: spec 05's `canRead`, spec 06's `findReadable`, `listReadable`, diff (viewers never receive
trashed entries) already cover recipients; nothing new.

### 1.5 Routes

`requireSession`, rate group `collections` (public key lookup: `users`): `POST /api/v1/collections`,
`DELETE /api/v1/collections/:id`, `POST /api/v1/collections/:id/entries`,
`DELETE /api/v1/collections/:id/entries/:entryId`, `POST /api/v1/collections/:id/shares`,
`GET /api/v1/collections/:id/shares`, `DELETE /api/v1/collections/:id/shares/:recipientUserId`,
`GET /api/v1/users/:username/public-key`.

### 1.6 Server tests (integration)

Flag off → `403 FEATURE_DISABLED` on every route above. With the flag on: create (repeat → `200`;
limit); add entry (foreign entry → `404`; trashed → `404`; repeat → `200`); share (self → `422`;
wrong sealed length → `422`; repeat identical → `200`; different key → `409`; limit); recipient's
`/sync/state` lists the collection with `role viewer`; `GET /collections/{id}` returns
`ownerUsername`; recipient's diff
returns the entry; recipient `GET /objects/:id/download` for its media → `200`; recipient cannot
`PATCH`, trash or add to it (`404`); remove entry → tombstone, recipient's next diff `removed`;
recipient leaves → no longer listed; owner unshares → same; delete collection → gone from both
`/sync/state` lists, entry still in the owner's home; public-key lookup of an unknown name → `404`.

## 2. Client

### 2.1 Sharing service (`src/services/sharing/sharing-service.ts`)

All methods require the account unlocked, the flag on (`config.get()?.sharingEnabled`) and network
(a `NetworkError` surfaces as `errors.sharingOffline` "You're offline. Sharing needs a connection.").

| Method | Steps |
|---|---|
| `createJournal(name)` | `id = randomId()`, `CK = randomBytes(32)`; `keyByOwner = wrapKey(sodium, MK, CK, collectionKeyByMaster, id)`; `metadataBlob = sealJson(sodium, CK, { schemaVersion: 1, name, createdAtUtc }, collectionMetadata, id)` (validated `CollectionMetadataV1`); `POST /collections`; on success insert the local `collections` row (role owner, `cursorSeq 0`, `metaUpdatedAt` from the response) |
| `renameJournal(id, name)` | owner; seal new metadata with `CK`; `PATCH /collections/{id}`; update the local row |
| `deleteJournal(id)` | owner; `DELETE /collections/{id}`; local: delete the collection row and its memberships (entries keep their home membership) |
| `addEntries(collectionId, entryIds)` | per id: not owned or not found locally → `failed`; already a local membership of this collection → `alreadyIn` (no request); `baseRevision === 0` → `notSynced`; else `entryKey` via `entryKeys`, `wrap = wrapKey(sodium, CK, entryKey, AD_CONTEXTS.entryKeyByCollection, \`${collectionId}/${entryId}\`)`, `POST /collections/{id}/entries`; `201`/`200` → `added` and insert the local membership (seq 0; the next pull brings the real seq); `409 ID_CONFLICT` (already added from another device; wraps never repeat because of the random nonce) → `alreadyIn`; `404` → `failed`. Returns `{ added, alreadyIn, notSynced, failed }` |
| `removeEntries(collectionId, entryIds)` | owner; `DELETE …/entries/{entryId}` each; delete local memberships |
| `lookupRecipient(username)` | `normalizeUsername`; `GET /users/{username}/public-key`; returns `{ userId, username, publicKey, fingerprint: publicKeyFingerprint(sodium, publicKey) }`; `404` → `errors.userNotFound` "No user with that username on this server." |
| `share(collectionId, recipient)` | owner; `payload = buildSharePayload(collectionId, CK)`; `sealed = sodium.boxSeal(payload, recipient.publicKey)`; `POST …/shares`; `409 ALREADY_SHARED` → `errors.alreadyShared` "Already shared with (name)." |
| `listShares(collectionId)` | `GET …/shares` |
| `unshare(collectionId, recipientUserId)` | `DELETE …/shares/{recipientUserId}` |
| `leave(collectionId)` | viewer; `DELETE …/shares/{myUserId}`; local: drop the collection, its memberships, and entries left without memberships (same code path as spec 08 §3.8 step 2) |
| `myFingerprint()` | `publicKeyFingerprint(sodium, myPublicKey)` |

After every successful call: `syncService.syncNow()` in the background (brings the real seqs and the
recipient-side changes), and `entriesChanged` for touched entries.

Collection names for display: `collectionNames.get(id): Promise<string>` and `clear(): void`
(`src/services/sharing/collection-names.ts`); a name that cannot be decrypted → `TEXT.shared.unnamed`
"Untitled journal";
opens `metadataBlob` with the collection key (owner: unwrap; viewer: sealed payload, spec 08
`entryKeys.collectionKey`) and caches the name in memory (cleared on lock: add a lock step
`clearCollectionNames` before `wipeKeys` in spec 16's table — applied there).

### 2.2 Index

Spec 11's index already stores `collectionIds` and supports `scope: collection`. Entries of other
users (`isOwn: false`) appear only in their collection's scope, never in `home`. `tagCounts` count
only own entries (spec 11), so a recipient's tag suggestions are not polluted by other people's
tags.

### 2.3 Screens

Tab `/(tabs)/shared` (label "Shared"; visible always; when the flag is off it shows "Sharing is
turned off on this server." and nothing else):

- Section "My Shared Journals": rows with the journal name, cover thumbnail (newest entry), entry
  count (from the index scope) and `recipientCount`; button "New Shared Journal…" (sheet with a name field, 1-100 chars,
  "Create").
- Section "Shared With Me": rows with the journal name and "from (ownerUsername)".
- Empty states: "You haven't shared anything yet." / "Nothing has been shared with you yet."

`/collections/[id]`: the gallery grid with `scope: { kind: 'collection', collectionId }` (spec 11
`EntryGrid`), header = journal name.
- Owner overflow: "Share With…" → `/collections/[id]/share`; "Rename…"; "Delete Journal…"
  (confirm "Delete (name)? The entries stay in your journal. People you shared it with lose
  access." / "Delete"). Selection mode opens spec 10's bulk sheet in mode `collectionOwner` (only
  "Remove from This Journal"). Viewers have no selection mode in a shared journal.
- Viewer: header subtitle "Shared by (ownerUsername)"; overflow "Leave…" (confirm "Leave (name)?
  You'll lose access unless (owner) shares it again."). Entry viewer (13) in this scope shows no
  edit/trash actions (role viewer) and "Shared by (owner)".

`/collections/[id]/share` (owner): 
1. Username field + "Find" → `lookupRecipient`.
2. Result card: username and the **security code** (fingerprint) in large monospace, with the text
   "Ask (username) to open Settings › Account and read you their security code. If it matches,
   nobody else can read what you share." and buttons "Share" and "Cancel". (Comparison is
   optional; this is the mitigation of the key-substitution risk, security.md §1.1.)
3. List of current recipients with "Remove" (confirm "Stop sharing with (name)? They may have
   kept copies of what they already saw." — D-31 wording).

Adding entries — `src/features/sharing/share-entries-sheet.tsx` (`ShareEntriesSheet`, props
`{ entryIds }`), opened from the entry viewer's "Share…" (13) and from the gallery bulk sheet's
"Share…" action (10 §4.2 `journal` mode gains "Share…" when either flag is on). Rows:
- "Add to Shared Journal…" (when `sharingEnabled`, else disabled with "Sharing with people on this
  server is turned off.") → list of the owner's shared journals and "New Shared Journal…" →
  `addEntries` → `Banner` "(added) added." plus "(notSynced) not synced yet — try again shortly."
  when > 0 and "(alreadyIn) were already there." when > 0.
- "Create Public Link…" (spec 18; when `publicLinksEnabled`, else disabled with "Public links are
  turned off on this server."; absent until spec 18 is built).

Settings › Account (spec 20) shows "Your security code" = `myFingerprint()`.

### 2.4 Text catalog additions

Keys used above: `shared.tab`, `shared.flagOff`, `shared.mine`, `shared.withMe`, `shared.new`,
`shared.emptyMine`, `shared.emptyWithMe`, `shared.from`, `shared.shareWith`, `shared.rename`,
`shared.delete`, `shared.deleteConfirm`, `shared.leave`, `shared.leaveConfirm`, `shared.removeFrom`,
`shared.securityCodeHelp`, `shared.stopConfirm`, `shared.addTo`, `shared.added`, `shared.notSynced`,
`errors.sharingOffline`, `errors.userNotFound`, `errors.alreadyShared`. Exact strings as quoted in
§2.1-2.3.

## 3. Changes to other docs (applied there already)

- `api.md` §4: public-key response without `fingerprint` (the client computes it).
- `api.md` §5: `CollectionDto.ownerUsername`; recipients may delete their own share.
- spec 16 §1: lock step `clearCollectionNames` before `wipeKeys`.
- spec 08 / `sync.md`: sharing is not an outbox kind (D-64).

## 4. Client tests

Server additions to §1.6: unshare by a third party → `404`; `deleteCollection` on `home` → `403`;
public key of a `pending_deletion` user → `404`; `kind = link` create with `sharing` off and
`public_links` on → `201`.

Node, memory adapters, fake HTTP (plus: `removeEntries`, `deleteJournal`, `alreadyIn` via local
membership and via `409`, `collectionNames` cleared on lock):
1. `createJournal` → request carries a 73-byte `keyByOwner` that unwraps to `CK` with the master key
   and a metadata blob that opens to the name.
2. `share` → sealed key opens with the recipient's private key via `openSharePayload` to `CK`.
3. `addEntries` skips `baseRevision 0` entries; the wrap opens with `CK` to the entry key.
4. `leave` drops entries only reachable through that collection; an entry also in the home
   collection (own entry) is kept.
5. `lookupRecipient` fingerprint equals `publicKeyFingerprint` of the returned key.

e2e (`tests/e2e`, two accounts A and B with the flag on): A creates a journal, adds 2 entries,
shares with B → B syncs and sees both entries (decrypts metadata and one original); A removes one
entry → B's next sync no longer has it; B leaves → B has no entries of A; A trashes an entry in the
journal → B no longer sees it; A restores it → B sees it again.

## 5. Verification checklist

- [ ] `pnpm verify`, server integration and e2e green.
- [ ] Two real accounts (Android + web): share a journal, compare the security code on both
      screens (they match), recipient browses and plays a video.
- [ ] Flag off on the server → Shared tab shows the hint; entry viewer "Share…" disabled with hint.
- [ ] DB check after sharing: `collection_shares.sealed_collection_key` is 104 bytes; no column
      holds the collection name in plaintext (grep the dump for the name: zero hits).
