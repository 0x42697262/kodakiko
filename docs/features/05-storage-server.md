# 05 — Storage server: S3 adapter, objects, quota, object jobs

Status: draft 1 (2026-11-11).

Goal: the server side of object storage: the `ObjectStore` adapter over S3, the object lifecycle
(`pending` → `uploaded` → `deleting` → gone), quota reservation, presign / parts / confirm /
download routes, and the jobs `abort-stale-uploads`, `collect-objects`, `reconcile-usage`. Entry
and media routes come in spec 06; this spec creates media rows only through test fixtures.

Read first: `docs/design/storage.md` (all); `docs/design/api.md` §7; `docs/design/data-model.md`
§§2.9-2.10, 5; spec 03 conventions. Depends on spec 03 (and spec 04 for `requireSession`).

## 1. Contract (`packages/contract/src/objects.ts`, `routes.ts`)

- `OBJECT_VARIANTS = ['original', 'thumbnail'] as const`, `OBJECT_STATES = ['pending', 'uploaded',
  'deleting'] as const`.
- Schemas from `api.md` §7: `presignRequest { id, mediaId, variant, sizeBytes }`,
  `presignResponse` (discriminated union on `mode`), `partsResponse { partSizeBytes, uploadedParts, partUrls, expiresAt }`, `objectDto`, `downloadResponse { url, expiresAt }`.
  `sizeBytes`: int ≥ `MIN_OBJECT_BYTES`, a new constant `MIN_OBJECT_BYTES = 40` in contract
  `limits.ts` (contract must not import core). A core test asserts it equals
  `STREAM_MIN_OBJECT_BYTES`.
- Routes: `objectsPresign`, `objectParts(id)`, `objectConfirm(id)`, `objectDownload(id)`.

## 2. Settings (`config/settings.ts`, group `storage`)

| Variable | Type | Default | Path |
|---|---|---|---|
| `STORAGE_PROVIDER` | `garage`/`b2`/`r2`/`s3` | required | `storage.provider` |
| `S3_ENDPOINT` | URL | required | `storage.endpoint` |
| `S3_PUBLIC_ENDPOINT` | https URL (http allowed only when `REQUIRE_HTTPS=false`) | required | `storage.publicEndpoint` |
| `S3_REGION` | string | `garage` | `storage.region` |
| `S3_BUCKET` | string 3-63 | required | `storage.bucket` |
| `S3_ACCESS_KEY_ID` | string | required | `storage.accessKeyId` |
| `S3_SECRET_ACCESS_KEY` | string | required | `storage.secretAccessKey` |
| `S3_FORCE_PATH_STYLE` | boolean | `true` | `storage.forcePathStyle` |
| `STORAGE_REQUEST_TIMEOUT_MS` | int ≥ 1000 | 15000 | `storage.requestTimeoutMs` |
| `SINGLE_PART_MAX_BYTES` | int ≥ 5 MiB | 8388608 | `storage.singlePartMaxBytes` |
| `MULTIPART_PART_BYTES` | int ≥ 5 MiB | 8388608 | `storage.multipartPartBytes` |
| `UPLOAD_URL_TTL_SECONDS` | int 60-604800 | 86400 | `storage.uploadUrlTtlSeconds` |
| `DOWNLOAD_URL_TTL_SECONDS` | int 60-86400 | 900 | `storage.downloadUrlTtlSeconds` |
| `PENDING_UPLOAD_HOURS` | int ≥ 1 | 48 | `storage.pendingUploadHours` |
| `OBJECT_GRACE_DAYS` | int ≥ 0 | 7 | `storage.objectGraceDays` |
| `MAX_PRESIGNS_PER_HOUR` | int ≥ 1 | 1000 | `storage.maxPresignsPerHour` |
| `JOB_COLLECT_OBJECTS_INTERVAL_MINUTES` | int ≥ 1 | 10 | `jobs.collectObjectsIntervalMs` (minutes × 60,000) |
| `JOB_ABORT_STALE_UPLOADS_INTERVAL_MINUTES` | int ≥ 1 | 60 | `jobs.abortStaleUploadsIntervalMs` |
| `JOB_RECONCILE_USAGE_INTERVAL_MINUTES` | int ≥ 1 | 1440 | `jobs.reconcileUsageIntervalMs` |

Cross-field rules in the schema's `superRefine` (each failure names the variables):
1. `limits.maxUploadBytes <= multipartPartBytes × S3_MAX_PARTS` (`S3_MAX_PARTS = 10_000` in
   constants).
2. `pendingUploadHours × 3600 > uploadUrlTtlSeconds` (a pending upload is never aborted while its
   URLs are still valid).
3. `singlePartMaxBytes <= limits.maxUploadBytes`.
4. `singlePartMaxBytes <= multipartPartBytes` (the S3 host's request body limit in Caddy is
   `multipartPartBytes` + 1 MiB, `deployment.md` §2, so single PUTs must fit it too).

`S3_SECRET_ACCESS_KEY` is added to the log redaction list and never appears in `SettingsError`
messages.

## 3. Adapter (`adapters/storage/`)

`object-store.ts`: the interface of `storage.md` §1 (incl. `listParts`, `UploadedPart`,
`getObjectStream`).

`s3-object-store.ts`: `createS3ObjectStore(config: StorageSettings): ObjectStore`.
- Two `S3Client`s (`storage.md` §1): `internalClient` with `endpoint`, `presignClient` with
  `publicEndpoint`; both `region`, `forcePathStyle`, credentials, `requestHandler` with
  `requestTimeout: requestTimeoutMs`, `maxAttempts: 1` (retries are ours, below).
- Retries: `withRetry(operation, { attempts: STORAGE_RETRY_ATTEMPTS = 3, baseDelayMs: 200,
  capDelayMs: 2000 })` with full jitter, only around `headObject`, `listParts`, `deleteObject`,
  `headBucket`, `abortMultipartUpload` (idempotent). Never around `createMultipartUpload` or
  `completeMultipartUpload` (guidelines 7.1). Retries only on network errors and HTTP 5xx/429.
  Backoff function lives in `platform/backoff.ts` (pure, unit-tested, random source injected).
- `headObject` returns `null` on `NotFound`/404.
- `deleteObject` treats 404 as success.
- `getObjectStream(key, range)`: `GetObjectCommand` on the internal client (with `Range:
  bytes=start-end` when `range` is given); returns the body stream, `ContentLength` and the total size
  (from `ContentRange` or `ContentLength`); `NotFound` → `ObjectMissingError` (the content route maps it to `NOT_FOUND`).
- `presignPut(key, ttl)`: `PutObjectCommand({ Bucket, Key, ContentType: 'application/octet-stream' })`
  signed with `presignClient`. `presignPart(key, uploadId, partNumber, ttl)`:
  `UploadPartCommand`. `presignGet(key, ttl)`: `GetObjectCommand`.
- Errors from the SDK that are not handled above are wrapped in `StorageUnavailableError`, which
  the service maps to `503 STORAGE_UNAVAILABLE`.

`readiness`: `bucketCheck(store): ReadinessCheck` named `bucket` calling `headBucket()`;
registered in `main.ts` next to `databaseCheck`.

## 4. Domain (`domain/objects.ts`, pure)

```ts
export function objectKey(ownerId: string, objectId: string): string;
export function chooseUploadMode(sizeBytes: number, settings: UploadSizing): UploadPlan;
export function quotaAllows(usedBytes: number, quotaBytes: number | null, defaultQuotaBytes: number, requestedBytes: number): boolean;
export function partsComplete(plan: UploadPlan, uploaded: readonly { partNumber: number; sizeBytes: number }[]): boolean;
export function deleteAfter(now: Date, graceDays: number): Date;
export function staleUploadCutoff(now: Date, pendingUploadHours: number): Date;
```

- `UploadPlan` = `{ mode: 'single' } | { mode: 'multipart', partSizeBytes, partCount }`.
  `partCount = ceil(size / partSizeBytes)`; part numbers are 1-based (S3 rule).
- `partsComplete`: every part number `1..partCount` present; parts `1..partCount−1` exactly
  `partSizeBytes`; the last exactly `size − partSizeBytes × (partCount − 1)`.
- `objectKey` = `${OBJECT_KEY_VERSION}/${ownerId}/${objectId}` with `OBJECT_KEY_VERSION = 'v1'` in
  constants.

## 5. Repository (`adapters/db/objects-repository.ts`)

Every method takes the executor (`Kysely<Database>` or a `Transaction<Database>`) as its **first**
parameter, written `trx` below and omitted from the table for brevity, so callers (this spec and
spec 06) compose them inside their own transactions.

| Method | SQL behavior |
|---|---|
| `findById(id)` | row or null |
| `insertPending(row)` | insert; unique violation on `id` → `IdConflict`; on `objects_one_pending_per_variant` → `PendingExists` |
| `setMultipartUploadId(id, uploadId)` | update |
| `markUploaded(id, now)` | must be called inside a transaction: `SELECT … FOR UPDATE` the row; move any other `uploaded` row with the same `(media_id, variant)` to `deleting` with `delete_after`; set this one `uploaded`, `uploaded_at` |
| `markDeleting(id, deleteAfter)` | state `deleting` |
| `markDeletingByMediaIds(mediaIds, deleteAfter)` | every `pending` or `uploaded` object of these media → `deleting` (used by spec 06) |
| `adjustUsedBytes(userId, delta)` | `UPDATE users SET used_bytes = used_bytes + $delta` |
| `findMediaOwner(mediaId)` | `{ ownerId, entryId }` from `media` join `entries`, or null |
| `canRead(userId, objectId)` | true when the object's media belongs to an entry that has a membership in a collection the user owns or is a share recipient of (one SQL query with `EXISTS`) |
| `selectCollectable(now, limit)` | `state = 'deleting' AND delete_after < now` ordered by `delete_after` |
| `deleteRow(id)` | delete |
| `selectStalePending(cutoff, limit)` | `state = 'pending' AND created_at < cutoff` |
| `sumSizesByOwner()` | `SELECT owner_id, sum(size_bytes) … GROUP BY owner_id` |
| `setUsedBytes(userId, value)` | update |
| `countPresignsSince(userId, since)` | count of objects created by the user since `since` |

## 6. Service (`services/objects-service.ts`)

`createObjectsService({ db, store, settings, clock, onObjectUploaded })` where
`onObjectUploaded: (trx: Transaction<Database>, entryId: string) => Promise<void>` is a no-op in this
spec and `sequenceService.bumpEntry` from spec 06 later. `confirm` step 5 opens one transaction:
`markUploaded(trx, id, now)` then `onObjectUploaded(trx, entryId)`. Ordered checks per guidelines
3.2.

| Method | Steps |
|---|---|
| `presign(userId, { id, mediaId, variant, sizeBytes })` | 1 `sizeBytes > maxUploadBytes` → `PAYLOAD_TOO_LARGE`; 2 media owner = user, else `NOT_FOUND`; 3 presigns in the last hour ≥ `maxPresignsPerHour` → `RATE_LIMITED`; 4 existing row with this id: owned, `pending`, same media/variant/size → re-issue URLs (step 7) without new reservation (multipart: URLs for every part number of the same plan, using the stored `multipart_upload_id`; no new `createMultipartUpload`); different → `ID_CONFLICT`; `uploaded` → `ID_CONFLICT`; 5 transaction: lock user row (`FOR UPDATE`), `quotaAllows` else log `object.quota_refused` (userId, requested, used, quota) and `QUOTA_EXCEEDED`, insert pending, `used_bytes += sizeBytes`; 6 multipart: `createMultipartUpload` **after** commit, store upload id (failure → mark the row `deleting` with `delete_after = now`, `STORAGE_UNAVAILABLE`); 7 presign URLs with `uploadUrlTtlSeconds`; log `object.presigned` with userId, objectId, sizeBytes, mode |
| `parts(userId, id)` | owner + `pending` + multipart, else `NOT_FOUND` (single-PUT objects resume by calling `presign` again, which re-issues the URL for the same pending row); `listParts`; return uploaded parts and fresh URLs for missing numbers |
| `confirm(userId, id)` | 1 owner check (`NOT_FOUND`); 2 `uploaded` → return as is; `deleting` → `NOT_FOUND`; 3 single: `headObject` null → `UPLOAD_INCOMPLETE`; multipart: `listParts`, not `partsComplete` → `UPLOAD_INCOMPLETE`; else `completeMultipartUpload` (if it fails because the upload no longer exists, fall back to `headObject`: present → continue, absent → `UPLOAD_INCOMPLETE`); 4 `headObject` size ≠ `sizeBytes` → `markDeleting(now)` + `UPLOAD_SIZE_MISMATCH`; 5 `markUploaded`; 6 bump the membership sequence for the entry — **hook** `onObjectUploaded(trx, entryId)`, injected, called inside the same transaction as `markUploaded`: the service opens the transaction and calls `markUploaded(trx, id, now)` (§5 signature) then `onObjectUploaded(trx, entryId)`; a no-op in this spec, implemented in spec 06; log `object.confirmed` |
| `download(userId, id)` | `canRead` and state `uploaded`, else `NOT_FOUND`; presign GET with `downloadUrlTtlSeconds` |

Objects never reach `deleting` through these routes except the size mismatch and the failed
multipart creation; media/entry deletion (spec 06) calls `markDeleting` through its own service.

## 7. Jobs

| Job | Lock id | Run |
|---|---|---|
| `abort-stale-uploads` | 1101 | `selectStalePending(cutoff, JOB_BATCH_SIZE)`; for each: multipart → `abortMultipartUpload` (ignore 404); `markDeleting(now)` (so GC deletes any single-PUT bytes and releases the reservation) |
| `collect-objects` | 1102 | `selectCollectable(now, JOB_BATCH_SIZE)`; for each: `deleteObject`; then one transaction: `deleteRow` + `adjustUsedBytes(owner, −size)`. A failed bucket delete leaves the row for the next run and logs `object.delete_failed` |
| `reconcile-usage` | 1103 | `sumSizesByOwner()`; for each user whose `used_bytes` differs: `setUsedBytes`, log `usage.reconciled` with old and new values; users with no objects set to 0 |

Each returns `{ processed, remaining: processed === JOB_BATCH_SIZE }` (reconcile: `remaining:
false`). Lock ids in `jobs/keys.ts`. All registered in `main.ts`.

## 8. Routes

`http/routes/objects.ts` with `requireSession` and rate-limit group `media`:
`POST /api/v1/objects/presign`, `GET /api/v1/objects/:id/parts`, `POST /api/v1/objects/:id/confirm`,
`GET /api/v1/objects/:id/download`. Controllers 5-15 lines.

CLI command `apply-bucket-cors` (in `cli.ts`'s `COMMANDS`): builds settings, calls
`PutBucketCorsCommand` on the internal client with the rule of `deployment.md` §2.1 and the origin
= `PUBLIC_BASE_URL`, then `GetBucketCorsCommand` and prints the applied rule; exit 0. Idempotent.
Used by the dev stack (spec 07b) and production (spec 21). An integration test runs it against the
test Garage and reads the rule back.

## 9. Test harness additions

`infra/compose/docker-compose.test.yml` gains service `garage-test` (image `dxflrs/garage:v2.5.0`,
digest recorded), with a minimal single-node `garage.toml` in `infra/compose/test/garage.toml`
(replication factor 1, `s3_api` on port 3900, `rpc_secret` and admin token fixed test values) and
port `127.0.0.1:53900:3900`. `test/integration/global-setup.ts` additionally runs, once and
idempotently, the Garage CLI steps inside the container (`docker compose exec garage-test
/garage …`): assign the node layout, create bucket `kodakiko-test`, create key `test`, allow
read/write; it writes the key id/secret to a gitignored `apps/server/test/.garage-test-key.json`.
Exact Garage commands: check the v2.5.0 "quick start" documentation at implementation time and
record them in `docs/facts.md` as `F-S3-06` with the URL.

`createTestObjectStore()` returns a real `S3ObjectStore` with `endpoint = publicEndpoint =
http://127.0.0.1:53900` and `REQUIRE_HTTPS=false` settings.

Fixture helpers (test-only, raw Kysely inserts allowed): `insertUserFixture(db)` inserts a user
with fake hashes and a home collection and returns `{ userId, homeCollectionId, sessionToken }`
(it also inserts a session); `insertMediaFixture(db, { ownerId, homeCollectionId })` inserts an
entry (`purpose 'journal'`, revision 1, random blob), a membership with the next seq, and a media
row, and returns `{ entryId, mediaId }`. Call it several times for several media.

## 10. Tests

Unit: `objectKey`; `chooseUploadMode` at `singlePartMaxBytes` and +1; `partsComplete` (missing
part, wrong middle size, wrong last size, extra part); `quotaAllows` with `null` quota (default)
and explicit 0; backoff bounds; settings cross-field rules (each failing case names the variables).

Integration (Postgres + Garage):
1. Single upload: presign → `fetch` PUT 1000 bytes to the URL with header `Content-Type:
   application/octet-stream` (it is part of the signature) → confirm → `uploaded`, `used_bytes`
   +1000 exactly once (presign 1000, confirm 0) → download URL fetch returns the same bytes;
   `Range: bytes=10-19` returns 10 bytes with `206`.
2. Multipart: size `2 × part + 123`; upload part 1 only → confirm → `422 UPLOAD_INCOMPLETE`; `parts`
   lists part 1 and returns URLs for 2 and 3; upload them → confirm → `uploaded`; the downloaded
   object equals the concatenation.
3. Size mismatch: presign 1000, PUT 999 bytes → confirm → `422 UPLOAD_SIZE_MISMATCH`, row
   `deleting`; after `collect-objects` with the clock past grace: row gone, bucket key gone,
   `used_bytes` back to 0.
4. Quota: user quota 1500; presign 1000 ok; presign 600 → `403 QUOTA_EXCEEDED`; concurrent
   presigns of 1000 and 1000 (Promise.all) → exactly one succeeds.
5. Idempotency: presign twice with the same id/size → same row, `used_bytes` reserved once;
   different size → `409 ID_CONFLICT`; confirm twice → second returns `uploaded` unchanged.
6. Replacement: confirm a second thumbnail for the same media → the first moves to `deleting`.
7. Access: another user's presign for the media → `404`; download of an object of an entry not
   shared with them → `404`; same response body for a random unknown id.
8. Oversize: `sizeBytes = maxUploadBytes + 1` → `413 PAYLOAD_TOO_LARGE`, no row.
9. `abort-stale-uploads`: a multipart pending row older than the cutoff → upload aborted, row
   `deleting`; a younger one untouched.
10. `reconcile-usage`: corrupt `used_bytes` by hand → job restores the sum, logs
    `usage.reconciled`.
11. Readiness: an app built with an `S3ObjectStore` pointing at a closed local port
    (`http://127.0.0.1:1`) reports `503` with `failed: ['bucket']`. No container is stopped.
12. Presigned URL host: the URL returned by presign starts with `S3_PUBLIC_ENDPOINT`.

## 10a. Provider conformance suite

`test/provider/provider.test.ts`, script `test:provider` (vitest project `provider`, not run in CI by
default): reads `PROVIDER_TEST_ENDPOINT`, `PROVIDER_TEST_PUBLIC_ENDPOINT`, `PROVIDER_TEST_REGION`,
`PROVIDER_TEST_BUCKET`, `PROVIDER_TEST_ACCESS_KEY_ID`, `PROVIDER_TEST_SECRET_ACCESS_KEY`,
`PROVIDER_TEST_FORCE_PATH_STYLE`, `PROVIDER_TEST_ORIGIN`; skips with a message when unset. Against
the real bucket: single presigned PUT; presigned multipart (`UploadPart`, F-S3-03), `ListParts`,
`CompleteMultipartUpload`; `HeadObject` size; Range GET of a presigned URL; `PutBucketCorsCommand`
then a CORS preflight `OPTIONS` with `Origin: PROVIDER_TEST_ORIGIN` and `Access-Control-Request-Headers:
range`; `getObjectStream`; cleanup. The suite must pass for a provider before it is used in production
(spec 21 release checklist item 12). The same suite runs in the normal integration run against the
test Garage (variables set by the harness).

## 10b. Implementation answers (2026-11-12)

1. `BUCKET_CORS_RULE.headers` = `['range', 'content-type']` (lowercase, F-S3-08).
2. The multipart `UploadPlan` carries `sizeBytes` (needed by `partsComplete` for the last-part size).
3. The `MAX_UPLOAD_BYTES ≤ MULTIPART_PART_BYTES × 10000` settings rule stays although current
   bounds cannot violate it; no failing-case test.
4. Garage keys get `--owner` (F-S3-06, D-78).
5. Accepted: `markUploaded(trx, id, now, replacedDeleteAfter)`; `PendingExists` → `ID_CONFLICT`;
   const `objectsRepository` with `insertPending` returning `'inserted' | 'idConflict' |
   'pendingExists'`; `confirm` falls back to `headObject` when the multipart upload is gone;
   `AppDeps.objectStore` required; the objects service takes a `logger`.

## 11. Steps

1. Contract + core equality test. 2. Settings group + rules + unit tests. 3. Backoff, domain +
unit tests. 4. Adapter, repository, service, routes, jobs. 5. Test compose + Garage bootstrap +
`F-S3-06`. 6. Integration tests. 7. `pnpm verify` + integration suite.

## 12. Verification checklist

- [ ] Suites green (paste summaries).
- [ ] `F-S3-06` recorded with the Garage commands and their documentation URL.
- [ ] Garage image digest recorded in `docs/dependencies.md`.
- [ ] Integration test 4 (concurrent quota) passed 10 times in a row (`--repeat` or a loop):
      reservation is race-free.
- [ ] No route returns an internal endpoint URL (test 12).
- [ ] Logs from the suite contain no `S3_SECRET_ACCESS_KEY` value and no presigned URL query
      string (`X-Amz-Signature` grep → zero hits).

## 13. Done when

Checklist fully reported; suites green.
