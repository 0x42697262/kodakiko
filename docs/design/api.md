# API

Status: draft 2 (2026-11-11, after review 1). Normative for `packages/contract/src/` (Zod schemas,
shared by server and client) and `apps/server/src/http/`. Every route is defined once in `contract`
and bound to the server through `fastify-type-provider-zod` (F-TOOL-07).

## 1. Conventions

| Topic | Rule |
|---|---|
| Base path | `/api/v1`, served on the app host (`https://kodakiko.<domain>/api/v1`, `deployment.md` §2). Additive changes stay in v1; breaking changes go to `/api/v2` with a deprecation window (guidelines 4.4) |
| Transport | JSON over HTTPS only (D-29). Caddy terminates TLS; the API trusts `X-Forwarded-Proto` from the proxy and answers `400 HTTPS_REQUIRED` when it is not `https`, unless `REQUIRE_HTTPS=false` (local development only) |
| Auth | `Authorization: Bearer kdk_s_<base64url>`; sessions in Postgres (`data-model.md` §2.2). No cookies, so no CSRF (guidelines 9.3) |
| Public-link auth | `X-Kodakiko-Link-Token: <base64url>` on `/public-links/{linkId}` viewer routes only; no bearer token |
| Ids | All path and body ids match `^[A-Za-z0-9_-]{22}$` unless stated; the server rejects others with `422 VALIDATION_FAILED` (Zod issue on that field) |
| Binary | base64url without padding in JSON bodies (`Bytes` in `packages/contract/src/ids.ts`) |
| Pagination | cursor-based. Requests take `cursor` (opaque string) and `limit`; responses return `items`, `nextCursor` (null when done). Hard max `limit` per route in `apps/server/src/config/constants.ts` |
| Idempotency | creates use the client-generated id as the key: a repeat with identical content returns the existing resource with `200` instead of `201`; a repeat with different content is `409 ID_CONFLICT` (`sync.md` §6) |
| Time | ISO 8601 UTC strings |
| Request id | generated per request, returned in `X-Request-Id`, logged |
| Rate limits | per route group, `@fastify/rate-limit`; keyed per session for authenticated requests and per IP otherwise (spec 03 §2.8, D-27); responses carry `X-RateLimit-Remaining` and, on `429`, `Retry-After` |
| Flags | a route behind an off flag answers `403 FEATURE_DISABLED`; the client never shows that as an error because it reads `/api/v1/config` first and renders the feature disabled with a hint (guidelines 4.5) |

### 1.1 Route groups and limits

| Group | Prefix | Limit (per IP, per window) |
|---|---|---|
| Auth | `/api/v1/auth` | 10 / 5 min (sign-in, recovery), 20 / hour (sign-up), 60 / min (others) |
| Users | `/api/v1/users` | 60 / min |
| Collections | `/api/v1/collections` | 120 / min |
| Entries | `/api/v1/entries` | 300 / min |
| Media and objects | `/api/v1/media`, `/api/v1/objects` | 300 / min |
| Public links | `/api/v1/public-links` | owner 60 / min; viewer: open 30 / min, entries 120 / min, content 1200 / min (per IP; spec 18 §1.5) |
| Admin | `/api/v1/admin` | 60 / min |

Values live in `apps/server/src/config/constants.ts`; the table documents the defaults.

## 2. Errors

One error shape for every non-2xx response:

```json
{ "error": { "code": "NOT_FOUND", "message": "…", "requestId": "…", "details": null } }
```

`code` comes from `packages/contract/src/errors.ts` (`ErrorCode` union); `message` comes from the
server text catalog (`apps/server/src/text/errors.ts`) and is safe to show. Denials use one code
that does not distinguish missing from forbidden (guidelines 3.3).

| HTTP | Codes |
|---|---|
| 400 | `MALFORMED_REQUEST`, `HTTPS_REQUIRED` |
| 401 | `UNAUTHENTICATED`, `SESSION_EXPIRED`, `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN`, `FEATURE_DISABLED`, `QUOTA_EXCEEDED`, `INVITE_INVALID`, `LINK_TOKEN_INVALID`, `LINK_EXPIRED`, `LINK_VIEW_EXPIRED`, `ACCOUNT_PENDING_DELETION` |
| 404 | `NOT_FOUND` (one code for missing and forbidden) |
| 409 | `REVISION_CONFLICT` (`details: { revision, metadataBlob }`), `ID_CONFLICT`, `USERNAME_TAKEN`, `ALREADY_SHARED` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `VALIDATION_FAILED` (`details: { issues }` from Zod), `KDF_PARAMS_TOO_WEAK`, `UPLOAD_SIZE_MISMATCH`, `UPLOAD_INCOMPLETE` |
| 429 | `RATE_LIMITED` |
| 500 | `INTERNAL` (generic message; details only in logs) |
| 503 | `STORAGE_UNAVAILABLE` |

`REVISION_CONFLICT` is the only place a ciphertext comes back inside an error; the client uses them for the merge in `sync.md` §5.

## 3. Auth

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| POST | `/auth/params` | `{ username }` | `200 { salt, opsLimit, memLimitBytes }` | Unknown usernames get deterministic fake values (`crypto.md` §5.2) |
| POST | `/auth/sign-up` | `{ userId, username, inviteToken \| null, deviceLabel, salt, opsLimit, memLimitBytes, loginKey, recoveryLoginKey, publicKey, masterKeyByKek, masterKeyByRecovery, recoveryKeyByMaster, privateKeyByMaster, homeCollection: { id, keyByOwner, metadataBlob } }` | `201 { session: SessionDto, user: UserDto }` | `userId` is client-generated (D-43) because the key wraps are bound to it (`crypto.md` §3); an existing id → `409 ID_CONFLICT`. Sign-up policy (`data-model.md` §2.3), KDF floor (`KDF_PARAMS_TOO_WEAK`), `USERNAME_TAKEN`. User, home collection and invite redemption in one transaction |
| POST | `/auth/sign-in` | `{ username, loginKey, deviceLabel }` | `200 { session, user, keyBundle: KeyBundleDto }` | Constant-time compare. Allowed when `user.status = 'pending_deletion'` (the client then shows only the cancel screen) |
| GET | `/auth/key-bundle` | — | `200 KeyBundleDto` | For web unlock with a stale cache (`crypto.md` §5.3) |
| POST | `/auth/recovery/start` | `{ username }` | `200 { userId, masterKeyByRecovery }` | The client needs `userId` because the wrap is bound to it. Unknown users get deterministic fakes of both (`crypto.md` §5.5) |
| POST | `/auth/recovery/complete` | `{ username, recoveryLoginKey, deviceLabel, salt, opsLimit, memLimitBytes, loginKey, masterKeyByKek }` | `200 { session, user, keyBundle }` | Verifies `SHA-256(recoveryLoginKey)`; revokes all other sessions |
| POST | `/auth/password` | `{ currentLoginKey, salt, opsLimit, memLimitBytes, loginKey, masterKeyByKek }` | `200 { session }` | Revokes all other sessions; returns a new session for this device |
| POST | `/auth/sign-out` | — | `204` | Deletes the current session |
| GET | `/auth/session` | — | `200 { user }` | Verifies a stored session before trusting it (guidelines 9.1) |

- `SessionDto` = `{ token, expiresAt, deviceLabel }`. The token is returned once; the server stores
  only its hash.
- `UserDto` = `{ id, username, role, status, quotaBytes, usedBytes, createdAt, deletionDueAt }`.
- `KeyBundleDto` = `{ salt, opsLimit, memLimitBytes, masterKeyByKek, recoveryKeyByMaster,
  publicKey, privateKeyByMaster }`.
- `deviceLabel`: 1-64 characters; the client sends `"Android"` or the browser name.

## 4. Users

| Method | Path | Response | Notes |
|---|---|---|---|
| GET | `/users/me` | `200 UserDto` | |
| GET | `/users/{username}/public-key` | `200 { userId, username, publicKey }` | For sharing (`crypto.md` §8.1). The **client** computes the security code with `publicKeyFingerprint` (spec 02 §10); the server has no crypto library and returns none. Unknown user → `404`. Lets signed-in users test whether a username exists; accepted on an invite-only server and recorded in `security.md` §1.2 |
| POST | `/users/me/delete` | `202 { deletionDueAt }` | `status = 'pending_deletion'`, `deletion_due_at = now + deletionGraceDays`, **all sessions revoked immediately** (D-29 "immediate lock") |
| POST | `/users/me/delete/cancel` | `200 UserDto` | Allowed only while pending. While pending, the authenticated routes that work are exactly `GET /auth/session`, `POST /auth/sign-out`, `GET /users/me` and this one; all others answer `403 ACCOUNT_PENDING_DELETION` |

## 5. Collections

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| POST | `/collections` | `{ id, kind: 'shared' \| 'link', keyByOwner, metadataBlob }` | `201 CollectionDto` | `shared` needs flag `sharing`; `link` needs flag `public_links`. `home` is created only by sign-up |
| GET | `/collections` | `?cursor&limit` | `200 { items: CollectionDto[], nextCursor }` | Owned collections plus those shared to the user. `link` collections only for their owner |
| GET | `/collections/{id}` | — | `200 CollectionDto` | `404` unless owner or recipient |
| PATCH | `/collections/{id}` | `{ metadataBlob }` | `200 CollectionDto` | Owner only; last writer wins (renames are rare and single-writer) |
| DELETE | `/collections/{id}` | — | `204` | Owner only; `home` cannot be deleted (`403 FORBIDDEN`). Entries stay in the owner's home collection |
| POST | `/collections/{id}/entries` | `{ entryId, entryKeyByCollection }` | `201` or `200` (already a member, same wrap) | Owner of both the collection and the entry; adds a membership with a new `seq` |
| DELETE | `/collections/{id}/entries/{entryId}` | — | `204` | Owner only; writes a tombstone. Not allowed on `home` (use trash/delete) |

`CollectionDto` = `{ id, kind, role: 'owner' \| 'viewer', ownerId, ownerUsername, keyMaterial, metadataBlob,
lastSeq, createdAt, metaUpdatedAt }`. `metaUpdatedAt` is the `meta_updated_at` column (changes only with key
material or metadata, `data-model.md` §2.4). `keyMaterial` is `keyByOwner` for the owner, or the recipient's sealed
share payload for a viewer (`crypto.md` §8.1).

### 5.1 Sharing (flag `sharing`, read-only, D-39)

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| POST | `/collections/{id}/shares` | `{ recipientUserId, sealedCollectionKey }` | `201 ShareDto` | Owner only; `kind = 'shared'` only; rejects self and more than `maxRecipientsPerCollection`; an identical repeat (same recipient, same sealed key) returns `200` with the existing share; a different sealed key for an existing recipient → `409 ALREADY_SHARED` |
| GET | `/collections/{id}/shares` | — | `200 { items: ShareDto[] }` | Owner only |
| DELETE | `/collections/{id}/shares/{recipientUserId}` | — | `204` | Owner, or the recipient removing themselves ("Leave"); no key rotation (D-31); absent share → `204` |

`ShareDto` = `{ recipientUserId, recipientUsername, createdAt }`. The sealed key is never returned to
the owner.

## 6. Entries

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| POST | `/entries` | `{ id, purpose: 'journal' \| 'link_copy', metadataBlob, memberships: [{ collectionId, entryKeyByCollection }], media: [{ mediaId, mediaKeyByEntry }] }` | `201 EntryDto`, or `200` for an identical repeat | `revision` starts at 1. `journal` entries must include a membership in the caller's home collection; `link_copy` entries exactly one membership in an owned `link` collection. `media` 1..`maxMediaPerEntry`. One transaction: entry, memberships (each with a new `seq`), media rows |
| GET | `/entries/{id}` | — | `200 EntryDto` | Readable through any membership |
| PATCH | `/entries/{id}` | `{ metadataBlob, baseRevision }` | `200 EntryDto` or `409 REVISION_CONFLICT` | Owner only. Increments `revision`; bumps every membership `seq` |
| POST | `/entries/{id}/trash` | — | `200 EntryDto` | Owner only; unconditional; idempotent |
| POST | `/entries/{id}/restore` | — | `200 EntryDto` | Owner only; unconditional; idempotent |
| DELETE | `/entries/{id}` | — | `204` | Owner only; permanent; tombstones in every collection; objects to `deleting`; absent entry → `204` |

`EntryDto` = `{ id, ownerId, purpose, revision, metadataBlob, trashedAt, createdAt, updatedAt }`.

## 7. Media and objects

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| POST | `/entries/{id}/media` | `{ mediaId, mediaKeyByEntry }` | `201 MediaDto` or `200` | Owner only; enforces `maxMediaPerEntry`; bumps membership `seq` |
| DELETE | `/media/{mediaId}` | — | `204` | Owner only; its objects to `deleting`; bumps membership `seq` |
| POST | `/objects/presign` | `{ id, mediaId, variant: 'original' \| 'thumbnail', sizeBytes }` | `200 PresignDto` | Owner only. Checks in this order (spec 05 §6): size cap (`413`), media ownership (`404`), presign rate (`429`), existing row for the id, quota (`403 QUOTA_EXCEEDED`); then inserts the `pending` row and adds `sizeBytes` to `used_bytes` in one transaction. Repeat for an existing `pending` id returns fresh URLs for the same upload |
| GET | `/objects/{id}/parts` | — | `200 { partSizeBytes, uploadedParts: [{ partNumber, sizeBytes }], partUrls: [{ partNumber, url }], expiresAt }` | Multipart resume: server calls `ListParts`; returns fresh URLs only for missing parts. Single-PUT objects resume by calling `presign` again |
| POST | `/objects/{id}/confirm` | — | `200 ObjectDto` | Single: `HeadObject`. Multipart: `ListParts` then `CompleteMultipartUpload` (not auto-retried; a repeat call finds the object complete via `HeadObject`). Then size check (`422 UPLOAD_SIZE_MISMATCH`, row to `deleting`) or missing parts (`422 UPLOAD_INCOMPLETE`, row stays `pending`). Success: row `uploaded`, any older `uploaded` row of the same media variant to `deleting`, membership `seq` bumped. Idempotent for an `uploaded` row |
| GET | `/objects/{id}/download` | — | `200 { url, expiresAt }` | Presigned GET (TTL `downloadUrlTtlSeconds`, 900). Works with an HTTP `Range` header; the web video worker uses it (`storage.md` §5). Readable through any membership |

- `PresignDto` = `{ mode: 'single', url, expiresAt } | { mode: 'multipart', partSizeBytes, partUrls: [{ partNumber, url }], expiresAt }`.
  Upload URL TTL `uploadUrlTtlSeconds` (default 86,400) so a background upload survives app
  suspension.
- `MediaDto` = `{ id, entryId, mediaKeyByEntry, createdAt }`.
- `ObjectDto` = `{ id, mediaId, variant, sizeBytes, state }`.

## 8. Sync

| Method | Path | Query | Response | Notes |
|---|---|---|---|---|
| GET | `/collections/{id}/diff` | `since` (int ≥ 0), `limit` (≤ `maxDiffPageSize`, default 500) | `200 DiffPage` | `sync.md` §4 |
| GET | `/sync/state` | — | `200 { collections: [{ id, kind, role, lastSeq, tombstonesPrunedThroughSeq, metaUpdatedAt }] }` | Every collection the user can read. A collection missing from the list was deleted or unshared: the client drops it. A changed `metaUpdatedAt` means its key material or metadata changed: the client refetches `GET /collections/{id}` |

```ts
export const diffPage = z.object({
  collectionId: idSchema,
  memberships: z.array(z.object({
    entryId: idSchema,
    entryKeyByCollection: bytesSchema.nullable(),
    seq: z.number().int().nonnegative(),
    removed: z.boolean(),
  })),
  entries: z.array(entryDto),
  media: z.array(mediaDto),
  objects: z.array(objectDto),
  nextCursor: z.number().int().nonnegative(),
  hasMore: z.boolean(),
  resyncRequired: z.boolean(),
});
```

- `removed: true` rows come from tombstones and have `entryKeyByCollection: null`.
- `entries`, `media` and `objects` hold the full rows for every non-removed membership in the page;
  `objects` contains only `uploaded` rows. One round trip per page, no N+1 (guidelines 7.2).
- Media whose objects are not uploaded yet (another device is still uploading) appear without
  objects; the client shows a placeholder until a later page brings them.

## 9. Public links (flag `public_links`)

Owner side (bearer auth). The client prepares everything first (`crypto.md` §8.2): `link`
collection, `link_copy` entries with their memberships and media, uploaded objects.

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| POST | `/public-links` | `{ collectionId, accessToken, expiry: LinkExpiry }` | `201 PublicLinkDto` | Owner of a `link` collection with at least one entry; stores `SHA-256(accessToken)` |
| GET | `/public-links` | — | `200 { items: PublicLinkDto[] }` | The owner rebuilds each URL from `keyByOwner` of the link collection |
| DELETE | `/public-links/{linkId}` | — | `204` | Revoke (D-48): link row, link collection, its `link_copy` entries; objects to `deleting` |

- `LinkExpiry` = `'1h' | '1d' | '7d' | '14d' | '30d' | 'never' | 'one_view'` (F-1P-03); `one_view`
  sets `max_views = 1` and no time limit.
- `PublicLinkDto` = `{ linkId, collectionId, expiresAt, maxViews, viewCount, createdAt }`.

Viewer side (`X-Kodakiko-Link-Token`, no bearer; `entries` and `download` also need the
`X-Kodakiko-View-Token` returned by `open`, a short-lived signed token, spec 18 §1.3):

| Method | Path | Response | Notes |
|---|---|---|---|
| POST | `/public-links/{linkId}/open` | `200 { collectionId, metadataBlob, expiresAt, maxViews, viewToken }` | Counts one view (transactional, `security.md` §4). Token mismatch or unknown link → `403 LINK_TOKEN_INVALID`; expired or view limit reached → `403 LINK_EXPIRED` (only after the token matched) |
| GET | `/public-links/{linkId}/entries` | `200 { items: [{ entry, entryKeyByCollection, media, objects: [{ …ObjectDto, download: { kind: 'presigned', url, expiresAt } \| { kind: 'proxy', path } }] }], nextCursor }` | Does not count a view; needs both tokens; expired view token → `403 LINK_VIEW_EXPIRED` |
| GET | `/public-links/{linkId}/objects/{objectId}/content` | ciphertext bytes (`application/octet-stream`) | Only with `PUBLIC_LINK_PROXY=true` (else `404`); both tokens |

`open` is a POST because it changes state (the view count).

## 10. Admin (role `admin`, D-27)

| Method | Path | Body | Response | Notes |
|---|---|---|---|---|
| GET | `/admin/users` | `?cursor&limit` | `200 { items: AdminUserDto[], nextCursor }` | `AdminUserDto` = `UserDto` + `sessionCount`, `lastActiveAt`. Never content |
| PATCH | `/admin/users/{id}` | `{ quotaBytes?: int \| null, role?: 'admin' \| 'member' }` | `200 AdminUserDto` | An admin cannot remove their own admin role; the last admin cannot be demoted (`422 VALIDATION_FAILED` with `details.reason` `selfDemotion` / `lastAdmin`) |
| POST | `/admin/invites` | `{ role, expiresInDays }` | `201 { inviteUrl, expiresAt }` | `inviteUrl` = `https://kodakiko.<domain>/invite#<token>`; shown once |
| GET | `/admin/invites` | — | `200 { items: InviteDto[] }` | `InviteDto` = `{ id, role, createdAt, expiresAt, redeemedAt, revokedAt, redeemedByUsername }` |
| DELETE | `/admin/invites/{id}` | — | `204` | Revoke |
| GET | `/admin/stats` | — | `200 { users, activeUsers30d, pendingDeletion, collections, entries, objects, totalBytes, quotaBytesAllocated }` | Counts only |

No admin route returns a key, a wrap or a blob of another user (`security.md` §5 item 2). Members
calling admin routes get `404 NOT_FOUND`, not `403` (spec 19 §1.2).

## 11. Operations

| Method | Path | Response | Notes |
|---|---|---|---|
| GET | `/health/live` | `200 { status: 'ok' }` | No dependencies checked |
| GET | `/health/ready` | `200` or `503` | Postgres `SELECT 1` and `HeadBucket`, each with a timeout |
| GET | `/api/v1/config` | `200 PublicConfigDto` | Unauthenticated: `{ apiVersion: 1, signupMode, sharingEnabled, publicLinksEnabled, maxMediaPerEntry, maxUploadBytes, defaultQuotaBytes, trashDays, accountDeletionGraceDays, serverVersion }`. The client reads it at start and on every foreground |

`/health/*` sit outside `/api/v1` and outside the rate limiter.

## 12. Contract layout

```
packages/contract/src/
  ids.ts            ID_PATTERN, ID_REGEX, idSchema/Id, bytesSchema/Bytes (spec 01)
  bytes.ts          encodeBytes / decodeBytes / isDecodableBase64Url (spec 01)
  constants.ts      base64url alphabet and bit widths (spec 01)
  limits.ts         shape limits shared by server and client (specs 03, 04, 05)
  enums.ts          USER_STATUSES, USER_ROLES (spec 04)
  errors.ts         ERROR_CODES / ErrorCode, errorResponseSchema (spec 03)
  config.ts         SIGNUP_MODES, publicConfigSchema (spec 03)
  payloads.ts       TagName, MediaDescriptorV1, EntryMetadataV1, CollectionMetadataV1 (spec 03)
  auth.ts           §3 schemas (spec 04)
  users.ts          §4 schemas (spec 04)
  objects.ts        §7 object schemas (spec 05)
  collections.ts    §5 schemas (spec 06; shares added by spec 17)
  entries.ts        §6 schemas (spec 06)
  media.ts          media schemas (spec 06)
  sync.ts           §8 schemas (spec 06)
  outbox.ts         OUTBOX_KINDS and payload schemas (spec 08)
  links.ts          §9 schemas (spec 18)
  admin.ts          §10 schemas (spec 19)
  routes.ts         ROUTE_PATTERNS + ROUTES, same keys (spec 03)
  index.ts
```

The server imports these schemas for validation and response serialization; the client imports them
for its typed HTTP adapter. One contract, two consumers (guidelines 8).
