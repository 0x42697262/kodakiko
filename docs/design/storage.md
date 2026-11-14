# Storage

Design reference written before the build (2026-11-11). Where it differs from the code, the code
and the feature specs' implementation notes are right.

Normative for `apps/server/src/adapters/storage/`, the client `FileStore` adapters, and the upload paths in `apps/client/src/services/`.

Covers: the bucket adapter and object keys, upload and download flows, multipart sizing, client
caches and eviction, and how the server never touches plaintext.

## 1. One adapter, many stores

The server talks to object storage through one interface
(`apps/server/src/adapters/storage/object-store.ts`):

```ts
export interface ObjectStore {
  readonly headObject: (key: string) => Promise<{ sizeBytes: number; etag: string } | null>;
  readonly presignPut: (key: string, sizeBytes: number, expiresInSeconds: number) => Promise<string>;
  readonly createMultipartUpload: (key: string) => Promise<string>;
  readonly presignPart: (key: string, uploadId: string, partNumber: number, partSizeBytes: number, expiresInSeconds: number) => Promise<string>;
  readonly listParts: (key: string, uploadId: string) => Promise<readonly UploadedPart[]>;
  readonly completeMultipartUpload: (key: string, uploadId: string, parts: readonly CompletedPart[]) => Promise<{ etag: string }>;
  readonly abortMultipartUpload: (key: string, uploadId: string) => Promise<void>;
  readonly presignGet: (key: string, expiresInSeconds: number) => Promise<string>;
  readonly deleteObject: (key: string) => Promise<void>;
  readonly headBucket: () => Promise<boolean>;
  readonly getObjectStream: (key: string, range: { readonly start: number; readonly end: number } | null) => Promise<{ readonly body: NodeJS.ReadableStream; readonly sizeBytes: number; readonly totalSizeBytes: number }>;
}

export type CompletedPart = { partNumber: number; etag: string };
export type UploadedPart = { partNumber: number; etag: string; sizeBytes: number };
```

One implementation, `S3ObjectStore`, over `@aws-sdk/client-s3` +
`@aws-sdk/s3-request-presigner` (F-TOOL-10). It holds **two** S3 clients with the same credentials:

- `internalClient` (`S3_ENDPOINT`, e.g. `http://garage:3900` on the compose network) for every call
  the server makes itself (`HeadObject`, `CreateMultipartUpload`, `ListParts`,
  `CompleteMultipartUpload`, `AbortMultipartUpload`, `DeleteObject`, `HeadBucket`);
- `presignClient` (`S3_PUBLIC_ENDPOINT`, e.g. `https://s3.kodakiko.<domain>`) used only to presign
  URLs, because SigV4 signs the `Host` header and devices reach the bucket through the public
  host. Caddy must forward the original `Host` to Garage unchanged (checked in spec 21). For B2
  and R2 both endpoints are the same URL.
 Every call has a timeout
(`settings.storage.requestTimeoutMs`, default 15 s) and the retry policy from guidelines 7.1:
retries with exponential backoff and jitter apply only to idempotent calls (`headObject`,
`presign*`, `deleteObject`, `headBucket`); `createMultipartUpload` and `completeMultipartUpload`
are **not** auto-retried (they mutate remote state and have no idempotency key).

Supported stores, chosen by config only (`settings.storage.provider`):

| Provider | Endpoint | Notes |
|---|---|---|
| `garage` | self-hosted, `infra/compose` | Multipart and presigned URLs implemented; **no bucket versioning** (F-S3-02) — the design does not need it (section 2) |
| `b2` | Backblaze S3 API | SigV4 only; buckets always versioned; presigned PUT/GET and multipart supported; presigned UploadPart UNVERIFIED (F-S3-03) — covered by an integration test in the spec |
| `r2` | Cloudflare R2 | S3-compatible |
| `s3` | any other S3-compatible | `S3_FORCE_PATH_STYLE` (default `true`, which Garage needs); set `false` for providers that require virtual-host style |

The bucket name, region, endpoint and credentials come from typed config (guidelines 4.3). The
server never stores a third-party credential beyond the bucket's own access key, which is a
first-party resource here (guidelines 9.3 does not apply: we own the bucket).

## 2. Object keys and immutability

Key layout: `v1/<ownerId>/<objectId>` (`data-model.md` §2.10).

- No media id, variant or extension in the key. The key is opaque; only `objects` rows know what
  it holds. This keeps the bucket listing useless to an attacker who obtains it and lets the same
  object id be reused safely (it never is: ids are random).
- **Objects are immutable** (D-34). A replaced thumbnail or an edited original is a new object with
  a new id; the old row moves to `state = 'deleting'`. Nothing overwrites a key.
- Because nothing is overwritten and deletion is delayed (trash + grace), bucket versioning is not
  required. Garage's missing versioning (F-S3-02) is therefore not a limitation. Operator backup is
  a scheduled `rclone sync` of the bucket to a second location (`docs/design/deployment.md`).

## 3. Upload flow

### 3.1 Choosing single PUT vs multipart

```
ciphertextSize = STREAM size of the plaintext (crypto.md §7.1)
if ciphertextSize <= settings.storage.singlePartMaxBytes  -> single PUT
else                                                       -> multipart
```

`singlePartMaxBytes` default 8 MiB (covers almost every photo and every thumbnail in one request).
Multipart part size: `settings.storage.multipartPartBytes`, default 8 MiB (S3 minimum is 5 MiB for
every part but the last). The **server** decides the part size and returns it in `PresignDto`;
the number of parts is `ceil(ciphertextSize / partSizeBytes)`. Part boundaries have no relation
to STREAM chunks: after completion the object is one byte sequence, and HTTP `Range` addresses it
as a whole.

Config validation at boot (guidelines 4.3) rejects `maxUploadBytes > multipartPartBytes × 10,000`
(the S3 part-count limit).

### 3.2 Sequence

1. The object is already encrypted in the local FileStore (captured entries are encrypted before
   they are saved, `architecture.md` §5.1); its size is known.
2. `POST /objects/presign { id, mediaId, variant, sizeBytes }`. The server checks the size cap and
   the quota, inserts the `pending` row, adds `sizeBytes` to `used_bytes` (the reservation), and
   returns a `PresignDto`. Multipart: the server calls `CreateMultipartUpload` and stores the
   upload id in the row.
3. Single PUT: the client `PUT`s the whole ciphertext to `url` with
   `Content-Type: application/octet-stream`. Multipart: the client `PUT`s each part to its URL
   (bounded concurrency `constants.UPLOAD_PART_CONCURRENCY`, 3). The client does **not** need the
   ETag response headers, so the bucket CORS rule does not have to expose them.
4. `POST /objects/{id}/confirm`. Single: `HeadObject`. Multipart: `ListParts` (Garage implements
   it, F-S3-05), check every part is present, then `CompleteMultipartUpload` with the listed ETags.
   Then compare the object size to `sizeBytes`: mismatch → `422 UPLOAD_SIZE_MISMATCH` and the row
   goes to `deleting`; missing parts → `422 UPLOAD_INCOMPLETE`, row stays `pending`. Success: row
   `uploaded`, older `uploaded` row of the same media variant to `deleting`, membership `seq`
   bumped, all in one transaction. Quota is not touched here (reserved at step 2).
5. Interrupted uploads: a single PUT is retried from scratch (same bytes, same key: safe). A
   multipart upload resumes with `GET /objects/{id}/parts`, which lists uploaded parts and returns
   fresh URLs for the missing ones. `abort-stale-uploads` (`data-model.md` §5) aborts uploads the
   client never finished and releases their reservation through GC.

### 3.3 Where the upload runs

| Platform | Foreground | Background |
|---|---|---|
| Android | `packages/native-upload` for **all** uploads, foreground and background: one code path. A foreground service (type `dataSync`, F-AND-01) with a progress notification ("Uploading N items") drains the queue until empty (D-33). It reads ciphertext files from the FileStore by path and PUTs byte ranges to presigned URLs; it never decrypts. Large ciphertext never crosses the JS bridge | same service; it keeps running when the app is backgrounded |
| Web | in-app `fetch` queue, bounded concurrency | none (the page must stay open). A closed tab resumes on next load from the outbox (`sync.md` §9) |

The native module receives a list of `{ objectId, parts: [{ partNumber, url, offset, length }],
filePath }` (a single PUT is one part with `partNumber: 0`) and reports per-part progress and
completion through an event emitter. It only moves bytes; presign and confirm stay in JS. It
retries network errors 3 times (after 2 s, 8 s, 30 s; spec 08 §4), stops on `403` (expired URL,
TTL 24 h) and reports it; JS re-requests URLs on the next foreground. Android service type:
`dataSync`; Android 15 time limits for that type are UNVERIFIED and are checked in spec 08, which
may switch the type.

## 4. Download flow

1. `GET /objects/{id}/download` → `{ url, expiresAt }` (presigned GET, TTL
   `settings.storage.downloadUrlTtlSeconds`, default 900 s).
2. The client writes the ciphertext to the FileStore (`downloadObject`, spec 14 §3), originals and
   thumbnails alike; in Android plain-thumbnail mode a downloaded thumbnail is then decrypted to
   the plain file and its encrypted copy deleted (spec 14 §5).

Access check: the server verifies the caller can read the object through some collection membership
before presigning. One `404 NOT_FOUND` covers "not yours, not shared, or doesn't exist"
(guidelines 3.3).

## 5. Web video playback (D-32)

The service worker (`apps/client/web/sw/`) makes an encrypted video seekable in a `<video>`
element without downloading the whole file:

1. On play, the client decrypts nothing itself. It generates a random `playbackId`, derives the
   `originalKey` variant key (`crypto.md` §7.1), and posts `{ playbackId, key, objectId,
   downloadUrl, plaintextSize, mimeType }` to the service worker. The key never touches storage;
   it lives in the worker's memory keyed by `playbackId` and is dropped on lock
   (`crypto.md` §9.2).
   - Browsers stop idle service workers (memory is lost). On a request for an unknown
     `playbackId` the worker posts `{ type: 'needKey', playbackId }` to the requesting page
     (`clients.get(event.clientId)`) and waits up to `SW_KEY_REQUEST_TIMEOUT_MS` (5 s); the page
     answers with the same message as above. No answer → the worker returns `410` and the player
     shows "Playback stopped. Tap to retry." (spec 15 §5).
   - The presigned URL expires after 15 minutes. On a `403` from the bucket the worker asks the
     page for a fresh `downloadUrl` the same way, then retries once.
2. The client sets `<video src>` to a synthetic URL
   `https://<app host>/__kdks_media/<playbackId>`.
3. The `<video>` element issues HTTP `Range` requests against that URL. The service worker
   intercepts them and, for each requested plaintext byte range:
   - maps it to STREAM chunk indices with `chunkRangeForPlaintextRange` (`crypto.md` §7.2);
   - before serving the first range, decrypts the final chunk once to prove the object is not
     truncated (age's seeking rule, `crypto.md` §7.2);
   - fetches only those ciphertext chunks from the bucket with one HTTP `Range` request on the
     presigned GET URL (byte offsets from `crypto.md` §7.1), decrypts each with `decryptChunk`,
     and slices the plaintext to the requested range;
   - caps one response at `SW_MAX_RESPONSE_CHUNKS` (4 chunks); an open-ended `bytes=N-` request
     gets a `206` for at most that window and the element asks for more;
   - answers the element with a `206 Partial Content` response carrying the right
     `Content-Range`, `Content-Length` and `Accept-Ranges: bytes`, and the video MIME type from the
     entry metadata.
4. Seeking causes a new `Range` request; the worker repeats step 3 for the new chunk window. Peak
   worker memory is a few chunks, independent of video length.

Constraints: a service worker only intercepts requests of documents it **controls**, and it controls
only pages whose URL is inside its scope; the app page is `/`, so the worker is registered with
scope `/` and calls `clients.claim()` on activation. Its `fetch` handler returns immediately (no
`respondWith`) for every request whose path does not start with `/__kdks_media/`, so API, bucket
and app-shell requests are never touched. It requires HTTPS (D-29). The MIME type must be a format the
browser can play from a byte stream (H.264/MP4 and WebM; HEVC support is device-dependent and
documented as a limitation in `docs/design/media.md`). The bucket CORS rule must allow the `Range`
request header and expose `Content-Range` and `Content-Length` (`deployment.md` §2.1). Chrome on
Android and desktop is the target; Safari's handling of media range requests through service
workers is UNVERIFIED and is tested in spec 15; browsers without a working worker use the Blob
fallback of D-62 (whole-file decrypt up to 200 MiB).

Android does not use the service worker. It decrypts the whole original to a temp file in
`tmp/` (`data-model.md` §4.1) and plays that file, deleting it on lock or app close (D-32). Start
delay grows with file length; acceptable for v1, and a future Android range-decrypt path can reuse
the same core stream functions (`crypto.md` §7.3).

## 6. Client caches

| Cache | Holds | Encrypted | Eviction |
|---|---|---|---|
| Thumbnails (Android, default) | WebP thumbnails as plain files, `thumbs-plain/<mediaId>.webp` | no (app-private, D-12) | never evicted (D-09); one copy per thumbnail (`data-model.md` §4.2) |
| Thumbnails (Android opt-in, web) | STREAM-encrypted thumbnail objects in the FileStore | yes | never evicted; on display, decrypted to a data URI (Android, F-LIB-09) or object URL (web) kept in an in-memory LRU of `THUMBNAIL_MEMORY_CACHE_ITEMS` (300) and revoked on eviction or lock |
| Originals | STREAM-encrypted original objects in the FileStore | yes | LRU by `lastAccessAt`, cap `settings.device.originalCacheCapBytes`; pinned entries and not-yet-uploaded objects are never evicted (`data-model.md` §4.2) |
| RowStore | ciphertext rows, outbox, cursors | yes (contents are ciphertext, D-37) | never evicted; it is sync state |

Network rules (D-61): thumbnails download on any network; originals on demand on any network;
pinned originals prefetch on Wi-Fi only; uploads above the configurable threshold (default 100 MiB) wait for Wi-Fi when the setting is on
(spec 14 §7). Cache cap `0` = no limit (D-67).

Presigned PUT and part URLs sign the exact `Content-Length` (`ContentLength` in the command), so a URL
cannot upload a different size and cannot resize an object later (spec 05 §10c).

Cache presence is tracked in `objects.localState`. A gallery scroll requests thumbnails with bounded
concurrency (`constants.THUMBNAIL_FETCH_CONCURRENCY`, default 6); a miss downloads, decrypts and
caches. The cache is a derived store with one documented rebuild path: delete it and re-pull
(guidelines 7.4).

"Keep offline" (D-09) writes a `pins` row; pinned entries' originals are downloaded eagerly and
exempt from eviction.

## 7. Garbage collection

The server never deletes a bucket object inline. Deletion is always a two-step state move
(`data-model.md` §2.10, §5):

1. A trigger (media removed, entry purged from trash, link revoked, account erased, size mismatch
   on confirm) sets the object row to `deleting` with `delete_after = now + objectGraceDays`.
2. The `collect-objects` job deletes rows whose `delete_after` has passed: `DeleteObject` then row
   delete then subtract `used_bytes`, in one transaction. A missing key deletes successfully, so
   the job is idempotent.

`objectGraceDays` (default 7) is separate from the 30-day trash: trash keeps the entry and its
objects recoverable; the grace period only covers objects already logically gone, so a crashed GC
does not resurrect them.

## 8. What the server sees

The server handles only ciphertext bytes and presigned URLs. It never:

- decrypts an object (it has no media key);
- reads a plaintext thumbnail;
- proxies media bytes in v1 (uploads and downloads go client ↔ bucket directly, so large files
  never pass through the API process). The one exception is public-link viewers behind a
  VPN-only ingress (D-36): if the bucket hostname is not reachable by the viewer, the share routes
  may proxy the ciphertext (`api.md` §9). Proxying ciphertext is still not decrypting.

This keeps the API process small and stateless (guidelines 7.2) and means a server compromise
yields ciphertext plus sizes and timing, not content (`architecture.md` §2).
