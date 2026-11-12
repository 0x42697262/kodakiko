# 18 — Public links (flag `public_links`)

Status: draft 1 (2026-11-11).

Goal: the owner selects entries and creates a link that anyone can open in a browser without an
account. The link carries the decryption key in its fragment (never sent to the server, D-10);
the server stores only a hash of a derived access token. Links contain stripped copies of exactly
the photos and videos the owner picks, with or without notes (D-65), expire by time or after one view, can be listed and revoked, and expired links clean
themselves up.

Read first: `docs/design/crypto.md` §8.2; `docs/design/api.md` §9; `docs/design/data-model.md`
§§2.6, 2.11; `docs/design/media.md` §6; `docs/design/storage.md` §8; `docs/design/deployment.md`
§6 (`PUBLIC_LINK_PROXY`); specs 05 (objects), 06 (entries, sequence), 09 (media toolkit, readers),
14 (originals), 17 (collections service, bulk "Share…" sheet); decisions D-10, D-36, D-48, D-64,
D-65. Depends on 15, 16 (route guard exclusion of `/s/…`) and 17.

## 1. Server

### 1.1 Settings and constants

| Variable | Type | Default | Path |
|---|---|---|---|
| `PUBLIC_LINK_PROXY` | boolean | `false` | `links.proxyDownloads` (stream ciphertext through the API instead of presigned URLs, for VPN-only buckets, `deployment.md` §6) |
| `MAX_PUBLIC_LINKS_PER_USER` | int 1-10000 | 100 | `links.maxPerUser` |
| `MAX_ENTRIES_PER_LINK` | int 1-500 | 100 | `links.maxEntriesPerLink` |
| `LINK_VIEW_TOKEN_MINUTES` | int 5-1440 | 60 | `links.viewTokenMinutes` |
| `JOB_EXPIRE_PUBLIC_LINKS_INTERVAL_MINUTES` | int ≥ 1 | 60 | `jobs.expirePublicLinksIntervalMs` |

Constants: `LINK_EXPIRY_DURATIONS_MS = { '1h': 3_600_000, '1d': 86_400_000, '7d': 604_800_000,
'14d': 1_209_600_000, '30d': 2_592_000_000 }`, `HMAC_LABELS.linkView = 'link-view-1'`,
`JOB_LOCK_IDS.expirePublicLinks = 1301`, `ONE_VIEW_RETENTION_MS = 86_400_000`.

### 1.2 Contract (`links.ts`, routes)

`LINK_EXPIRIES = ['1h', '1d', '7d', '14d', '30d', 'never', 'one_view'] as const`;
`publicLinkCreateRequest { collectionId, accessToken (32 bytes), expiry }`;
`publicLinkDto { linkId, collectionId, expiresAt (nullable), maxViews (nullable), viewCount,
createdAt }`; `publicLinkListResponse { items }`; `linkOpenResponse { collectionId, metadataBlob,
expiresAt, maxViews, viewToken }`; `linkEntriesResponse` per `api.md` §9; `downloadResponse` reused. Spec 17's `collectionCreateRequest.kind` already
accepts `'link'` (gated by `public_links`). Headers: `LINK_TOKEN_HEADER =
'x-kodakiko-link-token'`, `LINK_VIEW_TOKEN_HEADER = 'x-kodakiko-view-token'` (in `keys`). Routes:
`publicLinks()`, `publicLink(linkId)`, `publicLinkOpen(linkId)`, `publicLinkEntries(linkId)`,
`publicLinkObjectContent(linkId, objectId)`. Error code `LINK_VIEW_EXPIRED` (403) is added to the
contract's `ERROR_CODES` and `api.md` §2.

`api.md` §9 is extended by this spec (applied there): `open` returns `viewToken`; the two follow-up
viewer routes require **both** headers.

### 1.3 Viewer tokens (`domain/link-tokens.ts`, `platform/hash.ts`)

- Access check: `constantTimeEqual(sha256(accessTokenBytes), link.accessTokenHash)`.
- `viewToken` = `base64url(uint32be(expiresAtSeconds)) + '.' + base64url(hmacSha256(secret,
  'link-view-1', linkId + '.' + expiresAtSeconds))` with `expiresAt = now + viewTokenMinutes`
  (secret = `FAKE_PARAMS_SECRET`, distinct label). Valid when the HMAC matches (constant time) and
  `now < expiresAt`. Stateless: the API keeps no viewer sessions.
- Why: `one_view` counts a view at `open`; the entries and downloads that follow must work for that
  one viewer for a while without counting again (and without letting a second viewer in).

### 1.4 Service (`services/public-links-service.ts`)

All methods check `settings.flags.publicLinks` (else `FEATURE_DISABLED`).

| Method | Steps |
|---|---|
| `create(userId, { collectionId, accessToken, expiry })` | collection owned by user, `kind = link`, else `NOT_FOUND`; no link row for it yet (else `ID_CONFLICT`); it has ≥ 1 membership and ≤ `maxEntriesPerLink` (else `VALIDATION_FAILED`); `count(links of user) < maxPerUser` (else `VALIDATION_FAILED` with `details.limit`); every object of its entries' media is `uploaded` (else `UPLOAD_INCOMPLETE`); insert `{ id: randomId(), access_token_hash, expires_at: expiry ∈ durations ? now + d : null, max_views: expiry = one_view ? 1 : null }`; log `link.created` (userId, linkId, entryCount, expiry) |
| `list(userId)` | links joined with collections owned by the user |
| `revoke(userId, linkId)` | owner; `deleteLinkCollection(trx, collectionId)`; log `link.revoked` |
| `deleteLinkCollection(trx, collectionId)` | shared by revoke, the `DELETE /collections/{id}` route for `kind = link` (cleanup of a link whose creation failed; spec 17's service now allows `link` there and delegates here) and the expiry job: for each `link_copy` entry of the collection run spec 06's `removeEntryInTransaction(trx, entryId, now, settings.storage)` (objects to `deleting`, tombstones), then delete the collection (cascades the link row) |
| `open(linkId, accessToken)` | find link; missing or token mismatch → `LINK_TOKEN_INVALID` (same response for both, guidelines 3.3); then expired (`expires_at ≤ now`) or `max_views` reached → `LINK_EXPIRED`; then `UPDATE public_links SET view_count = view_count + 1 WHERE id = $1 AND (max_views IS NULL OR view_count < max_views) RETURNING view_count` — no row → `LINK_EXPIRED` (race-safe one view); return collection metadata and a `viewToken` |
| `entries(linkId, accessToken, viewToken, cursor, limit)` | token check (`LINK_TOKEN_INVALID`); view-token missing, malformed or expired → `403 LINK_VIEW_EXPIRED`; link revoked → `LINK_TOKEN_INVALID`; link time-expired → `LINK_EXPIRED` (one-view links stay readable for the view token's life); page of `link_copy` entries (limit default `LINK_ENTRIES_PAGE_DEFAULT = 50`, max `LINK_ENTRIES_PAGE_MAX = 100`) with `entryKeyByCollection`, media rows and uploaded objects; **each object carries its download**: `{ kind: 'presigned', url, expiresAt }` (presigned GET, `downloadUrlTtlSeconds`) or, with `proxyDownloads`, `{ kind: 'proxy', path: '/api/v1/public-links/<linkId>/objects/<objectId>/content' }` |
| `content(linkId, accessToken, viewToken, objectId)` | proxy mode only (else `NOT_FOUND`); same checks; object belongs to an entry of this link; streams the ciphertext (§1.5) |

Spec 06 rule reminder: `link_copy` entries are created with `POST /entries` (`purpose
'link_copy'`, exactly one membership in an owned `link` collection, flag on).

### 1.5 Routes

Owner (`requireSession`, group `publicLinks`): `POST /api/v1/public-links`, `GET
/api/v1/public-links`, `DELETE /api/v1/public-links/:linkId`.

Viewer (no session; one IP-keyed limiter per route, groups from `RATE_LIMITS`):

| Route | Group (default) |
|---|---|
| `POST /api/v1/public-links/:linkId/open` | `publicLinkOpen` (30 / min) |
| `GET /api/v1/public-links/:linkId/entries` | `publicLinkEntries` (120 / min) |
| `GET /api/v1/public-links/:linkId/objects/:objectId/content` (proxy mode only) | `publicLinkContent` (1200 / min: one call per photo and thumbnail) |

(`@fastify/rate-limit` runs only one limiter per request, verified in 11.2.2; a per-link limiter is
not used: the 256-bit link secret makes guessing pointless, and the IP limits bound load.) The old
`publicLinkViewer` entry of spec 03's `RATE_LIMITS` is replaced by these three.

Content route: `ObjectStore.getObjectStream(key, range)` (`storage.md` §1; spec 05 implements it:
internal client `GetObjectCommand` with `Range` when given); a request `Range: bytes=a-b` (single
range only; other forms → `416`) is passed through and answered `206` with `Content-Range`;
without `Range` → `200`; always `Content-Type: application/octet-stream`, `Content-Length`,
`Accept-Ranges: bytes`, `Cache-Control: no-store`, body piped. Videos in links are streamed and
seeked through this route by the media worker in proxy mode.

Viewer responses carry `Referrer-Policy: no-referrer` (already global) and `X-Robots-Tag: noindex`.

### 1.6 Job `expire-public-links` (lock 1301)

Links with `expires_at < now`, and one-view links with `view_count ≥ max_views` and `created_at <
now − ONE_VIEW_RETENTION_MS`, up to `JOB_BATCH_SIZE` per run: `deleteLinkCollection`. Log
`link.expired` with the count.

### 1.7 Server tests (integration)

Flag off → `403` everywhere. Happy path: owner creates link collection + a `link_copy` entry +
uploads + link → viewer `open` with the right token → `200` with `viewToken`; wrong token and unknown
link → identical `403 LINK_TOKEN_INVALID` bodies; `entries` without a view token → `403`; with it →
entries; entries carry presigned downloads (with `PUBLIC_LINK_PROXY=true`: proxy paths whose `content`
route returns the ciphertext bytes; without it the content route is `404`); an expired view token →
`403 LINK_VIEW_EXPIRED`;
`one_view`: two parallel opens → exactly one `200`, the other `403 LINK_EXPIRED`; the winner's
`entries` still work; time expiry with a moved clock → `LINK_EXPIRED`; revoke → link and copies
gone, objects `deleting`; job removes expired links; owner's home collection and originals
untouched by all of it. (The owner's `/sync/state` does list the `link` collection; the client
ignores it, spec 08 `planCollections`, tested there.)

## 2. Client

### 2.1 Sync rule (change to spec 08 §2.3, applied there)

`planCollections` ignores remote collections of `kind = 'link'`: link copies are never pulled into
the owner's RowStore or index.

### 2.2 Link creation service (`src/services/links/public-links-service.ts`)

```ts
export type CreateLinkInput = { readonly entryIds: readonly string[]; readonly mediaIds: ReadonlySet<string>; readonly title: string; readonly expiry: LinkExpiry; readonly includeNotes: boolean };
export type CreateLinkProgress = { readonly step: 'preparing' | 'uploading' | 'registering'; readonly done: number; readonly total: number; readonly bytesDone: number; readonly bytesTotal: number };
export type CreateLinkResult = { readonly url: string; readonly linkId: string; readonly skippedEntries: number; readonly skippedUndecodable: number; readonly skippedUnsupportedVideo: number };
export function createPublicLinksService(deps: { readonly http: HttpClient; readonly sodium: Sodium; readonly account: () => AccountKeys; readonly entries: EntriesService; readonly entryKeys: EntryKeys; readonly rowStore: RowStore; readonly fileStore: FileStore; readonly downloader: Downloader; readonly stripper: LinkStripper; readonly uploader: Uploader; readonly config: ConfigService; readonly randomId: () => string; readonly clock: Clock }): { create(input: CreateLinkInput, onProgress: (p: CreateLinkProgress) => void, signal: AbortSignal): Promise<CreateLinkResult>; list(): Promise<readonly OwnerLink[]>; revoke(linkId: string): Promise<void> };
```

`create` (online only, D-64), in order:
1. Validate: 1 ≤ entries ≤ `MAX_ENTRIES_PER_LINK` (client constant mirrors the server default), all
   own, synced (`baseRevision ≥ 1`), not trashed; `mediaIds` non-empty and every id belongs to one
   of the entries; title 1-100 chars (default `links.defaultTitle` "Shared moments").
2. Keys: `linkSecret = randomBytes(32)`; `linkCK = kdfDerive(32, linkCollectionKey, link,
   linkSecret)`; `accessToken = kdfDerive(32, linkAccessToken, link, linkSecret)`;
   `collectionId = randomId()`; `keyByOwner = wrapKey(sodium, MK, linkSecret, linkSecretByMaster,
   collectionId)`; metadata `{ schemaVersion: 1, name: title, createdAtUtc }` sealed with `linkCK`.
   `POST /collections` (`kind 'link'`).
3. For each entry with at least one selected media (`preparing`; entries whose selected media all
   fail count in `skippedEntries`), for each selected media in entry order:
   - make sure the original is local: `downloader.fetchObject(originalObjectId, 'opened')` (spec 14);
   - source = a `ByteSource` over the **decrypted** original, built by
     `createDecryptingByteSource(fileStore, objectId, variantKey, plaintextSize)` (new helper in
     `src/services/links/decrypting-source.ts`: random-access reads mapped to STREAM chunks through
     `decryptChunk`, an LRU of 4 decrypted chunks; plaintext stays in memory, never on disk);
   - `LinkStripper.strip(source, descriptor)` (§2.3) → `{ chunks: AsyncIterable<Uint8Array>,
     plaintextSize, mimeType, width, height, durationMs }` (photos: JPEG bytes; videos: the stripped
     container streamed);
   - thumbnail: reuse the media's existing thumbnail (our own metadata-free WebP, media.md §3):
     decrypt it (thumbnail variant key) and re-encrypt under the new media key;
   - new ids and a fresh media key; STREAM-encrypt original and thumbnail into the FileStore (as
     spec 09 step 9, the encryptor fed from `chunks`); a `MediaDescriptorV1` (`mimeType` from the
     stripper, `originalFileName null`, `capturedAtUtc null`, dimensions, `durationMs`).
   New `entryId`, `entryKey`; metadata = `{ schemaVersion 1, takenAtUtc and offset of the source
   entry, timeZone null, note: includeNotes ? note : '', tags: [], location: null, media,
   createdAtUtc/editedAtUtc now }`; seal; wraps (entry key by `linkCK`, binding
   `${collectionId}/${entryId}`; media keys by the entry key). `POST /entries` (`purpose
   'link_copy'`).
4. Uploads (`uploading`): `linkUploads.uploadAll(objects)` (`src/services/links/link-uploads.ts`):
   presign → `Uploader.enqueue` (spec 08 port) → wait for each `completed` → confirm; no RowStore
   rows (spec 08 ignores these ids); on success delete the local files. Progress in bytes.
5. Register (`registering`): `POST /public-links { collectionId, accessToken, expiry }`.
6. URL = `${serverBaseUrl}/s/${linkId}#${encodeBytes(linkSecret)}`; wipe `linkSecret`, `linkCK`,
   `accessToken`, keys.
7. Any failure or abort after step 2 → best-effort `DELETE /collections/{collectionId}` (server
   deletes copies and objects) and local files; rethrow.

`list()`: `GET /public-links` and `GET /collections/{id}` for each link collection's `keyMaterial`
(the owner's `keyByOwner`, cached per session) → unwrap the link secret with MK → rebuild the URL;
returns `{ linkId, url, title, expiresAt, maxViews, viewCount, createdAt }`. `revoke(linkId)`:
`DELETE /public-links/{linkId}`.

### 2.3 `LinkStripper` (`src/adapters/link-stripper/`, platform port) and core video rewriter

```ts
export type StrippedMedia = { readonly chunks: AsyncIterable<Uint8Array>; readonly plaintextSize: number; readonly mimeType: string; readonly width: number; readonly height: number; readonly durationMs: number | null };
export interface LinkStripper { readonly strip: (source: ByteSource, descriptor: MediaDescriptorV1) => Promise<StrippedMedia> }
```

**Photos** (`descriptor.kind === 'photo'`): the photo bytes are read fully from `source` (photos are
small), then:
- Native: written to `TempFiles.create('view', ext)` (spec 09 §4.1; wiped on lock) because the
  manipulator needs a URI → `ImageManipulator.manipulate(uri).resize(fitWithin(w, h,
  LINK_PHOTO_MAX_EDGE = 1600)).renderAsync()` → `saveAsync({ format: SaveFormat.JPEG, compress:
  LINK_PHOTO_QUALITY = 0.85 })` → read the result, delete both temp files.
- Web: `createImageBitmap(new Blob([bytes]), { imageOrientation: 'from-image' })` → `OffscreenCanvas`
  → `convertToBlob({ type: 'image/jpeg', quality: 0.85 })`.
- Decode failure (e.g. HEIC outside Safari) → `StripUndecodableError` → that media is skipped and
  counted in `skippedUndecodable`.
- Verification (mandatory, media.md §6): `readImageMetadata` on the JPEG has no date, offset or GPS,
  and `hasExifSegment(bytes)` (new core helper in `media/exif.ts`) is false; else `StripFailedError`
  and link creation stops.

**Videos** (`descriptor.kind === 'video'`), the same on both platforms (pure core code, no
re-encoding):
- MP4 and MOV: `stripIsoBmff(source)` from `packages/core/src/media/iso-bmff-strip.ts` (below).
- WebM: copied unchanged (WebM files from cameras and browsers carry no location; their only date
  field `DateUTC` is overwritten: `stripWebmDate(source)` rewrites the 8-byte `DateUTC` value in the
  `Info` element to 0 when present, a same-size in-place change).
- Other video containers cannot occur (spec 09 sniff table).
- Verification (mandatory): `readVideoMetadata` of the result (spec 09 §2.4) returns `creationUtc
  null` and `location null`, and the rewritten `moov` contains none of the box types `udta`, `meta`,
  `uuid`, `©xyz`; else `StripFailedError`.
- Fragmented MP4 (a `moof` box present) → `UnsupportedVideoError` → skipped, counted in
  `skippedUnsupportedVideo`.

`iso-bmff-strip.ts` (pure, streaming, bounded memory):

```ts
export type StripPlan = { readonly head: Uint8Array; readonly copyRanges: readonly { readonly offset: number; readonly length: number }[]; readonly outputSize: number };
export async function planIsoBmffStrip(source: ByteSource): Promise<StripPlan>;
export function streamStripped(source: ByteSource, plan: StripPlan, chunkBytes: number): AsyncIterable<Uint8Array>;
```

1. Walk top-level boxes (headers only, spec 09 §2.4 limits). Keep `ftyp`, `moov`, `mdat`, `free`,
   `skip`, `wide`; **drop** top-level `udta`, `meta`, `uuid` (XMP and vendor metadata) and any other
   unknown type. `moof` → `UnsupportedVideoError`. `moov` larger than `MOOV_MAX_BYTES` →
   `UnsupportedVideoError`.
2. Rewrite `moov` in memory: remove every `udta`, `meta` and `uuid` box at any depth; in `mvhd`,
   `tkhd` (each track) and `mdhd` set `creation_time` and `modification_time` to 0 (version 0:
   32-bit fields; version 1: 64-bit); recompute every parent box size.
3. Chunk offsets: if `moov` came **before** `mdat`, every `mdat` moves by `delta = newMoovSize −
   oldMoovSize + (bytes of dropped top-level boxes before that mdat)`: add `delta` to every entry of
   every `stco` (32-bit; if a value would overflow 32 bits → `UnsupportedVideoError`) and `co64`
   table. If `moov` came after all `mdat` boxes, offsets only shift by the dropped top-level boxes
   before each `mdat`, handled the same way.
4. Output order = original order of kept top-level boxes, with the rewritten `moov`; `head` = all
   kept non-`mdat` boxes that precede the first `mdat` (written from memory) and the plan's
   `copyRanges` copy each `mdat` (header included) and any kept boxes after it from the source in
   `chunkBytes` pieces (`STRIP_COPY_CHUNK_BYTES = 1_048_576`).

### 2.4 Screens (owner)

- Spec 17's `ShareEntriesSheet` row "Create Public Link…" (when `publicLinksEnabled`, else disabled
  with "Public links are turned off on this server.") opens `/links/new?entries=…`.
- `/links/new`: title field; **media picker**: every selected entry as a section (date heading,
  note preview) with a grid of its photo and video thumbnails, each with a checkbox (all checked at
  first; videos show their duration), "Select All" / "Select None" per entry and for the whole
  list; switch "Include notes" (on); expiry choice (segmented list: 1 hour, 1 day, 7 days (default),
  14 days, 30 days, Never, One view — F-1P-03); estimated upload size of the selected media; the
  standing warning "Anyone with the link can see what you selected. Location and photo/video
  details are removed. You can revoke the link later, but people may keep copies." (F-1P-05);
  "Create Link" (disabled when nothing is selected); progress view (steps plus bytes); on success
  the URL (selectable), "Copy" (web `navigator.clipboard.writeText`) / "Share…" (Android
  `Share.share({ message: url })`), "Done", and a line for each skipped count that is > 0.
- `/settings/links` (spec 20 links it): list of links with title, expiry ("Expires in 3 days" /
  "Never expires" / "One view — not opened yet" / "Opened"), view count, "Copy Link"/"Share…",
  "Revoke…" (confirm "Revoke this link? People who open it will see nothing. Copies already saved
  stay with them.").

### 2.5 Viewer page `/s/[linkId]` (web, no account)

Rendered **outside** `AppContextProvider`: `app/_layout.tsx` checks the pathname first and, for
`/s/…`, renders `<PublicViewerProvider>` (loads only `Sodium` via `loadSodium()` and an
`HttpClient` with `baseUrl = window.location.origin`, no session) instead of the app context; no
`restore()`, RowStore, FileStore or lock. 07a's `ApiCall.auth` gains the variant `{ linkToken:
string; viewToken: string | null }` (headers `X-Kodakiko-Link-Token` and, when set,
`X-Kodakiko-View-Token`).

1. Read `location.hash`, decode the secret (invalid → `links.viewerIncomplete` "This link is
   incomplete. Ask for the full link."), derive `linkCK` and `accessToken`, then
   `history.replaceState` without the fragment (the key leaves the address bar).
2. `POST …/open` → `LINK_TOKEN_INVALID` → `links.viewerInvalid` "This link doesn't work. It may have
   been revoked or mistyped."; `LINK_EXPIRED` → `links.viewerExpired` "This link has expired.";
   success → open the collection metadata with `linkCK`; page title = name; keep `viewToken`,
   `maxViews`.
3. `GET …/entries` (paginate until done) → unwrap entry keys with `linkCK`, open metadata, unwrap
   media keys.
4. Thumbnails and photos: fetch each object's `download` (`presigned` → plain `fetch(url)` with
   **no** custom headers, which the bucket CORS rule would reject; `proxy` → `fetch(path)` with both
   link headers); decrypt in memory; object URLs in an LRU of `LINK_VIEWER_IMAGE_CACHE_ITEMS = 200`.
   A `403` from a presigned URL (expired after 15 minutes) → re-fetch the entries page once to get
   fresh URLs, then retry.
5. Grid grouped by day (`formatDayHeader`; videos show a play glyph and duration); tap → full-screen
   view with the date and the note (`MarkdownView`, D-60); ←/→ and swipe. Photos: zoomable (spec 13
   §3.3a); "Download" saves the decrypted JPEG (`<a download="kodakiko-<n>.jpg">`). Videos: played
   with `expo-video` through the media service worker exactly as spec 15 §2.2 (the viewer page
   registers `/media-sw.js` itself; `register` carries the presigned `downloadUrl`, or, in proxy
   mode, the proxy path plus `extraHeaders` with both link tokens); without a working worker the
   Blob fallback of D-62 applies (≤ 200 MiB, else the message "This browser can't play this video.
   Try Chrome."); "Download" for a video decrypts it to a Blob first and is offered only up to
   `LINK_VIEWER_DOWNLOAD_MAX_BYTES = 209_715_200`.
6. Footer `links.viewerFooter` "Shared with Kodakiko. End-to-end encrypted: the server never saw
   these photos."
7. `LINK_VIEW_EXPIRED` later → `links.viewerSessionEnded` "This viewing session ended. Open the
   original link again." (when `maxViews === 1`: `links.viewerOneViewEnded` "This link could only be
   viewed once."); `LINK_TOKEN_INVALID` later → `links.viewerInvalid` (revoked).

## 3. Tests

Client (Node, fakes): `create` happy path (requests in order; the link copy metadata has `location:
null`, `tags: []`, note per option; only the selected media are copied; photo descriptors are JPEG,
video descriptors keep the video MIME type; thumbnails re-encrypted under the new media keys; secret
wiped); unselected media absent; an entry with no selected media is not copied; stripper
verification failure → creation stops and the cleanup `DELETE /collections/{id}` is sent; `list`
rebuilds the exact URL; URL parsing on the viewer; `createDecryptingByteSource` random reads across
chunk boundaries equal the plaintext.

Core: `hasExifSegment` on JPEGs with and without APP1 Exif. `iso-bmff-strip` with synthetic files
(built in the test): moov before mdat (faststart) and after mdat; `udta/©xyz`, `meta` (Apple keys),
track-level `udta`, top-level `uuid` and `udta` all removed; `mvhd`/`tkhd`/`mdhd` times zeroed for
versions 0 and 1; `stco` and `co64` entries shifted by exactly the size change so that the bytes each
offset points to in the output equal the bytes it pointed to in the input; `moof` →
`UnsupportedVideoError`; 32-bit `stco` overflow → `UnsupportedVideoError`; output re-read with
`readVideoMetadata` → no time, no location; streaming copy never allocates more than one copy chunk
per `mdat` read (byte-source fake with a virtual 2 GiB `mdat`). `stripWebmDate` on a synthetic WebM
`Info` with and without `DateUTC`.

e2e (`tests/e2e`, flag on): A creates a link for 2 entries (one photo carrying EXIF GPS, one MP4
video with `©xyz` and a creation time), selecting both media; a "viewer" (plain fetch + core crypto, no account) opens it
with the URL secret, decrypts both; `readImageMetadata` of the decrypted JPEG and `readVideoMetadata` of the decrypted
video show no GPS and no
date; `one_view` link: second open fails; revoke → open fails.

## 4. Verification checklist

- [ ] `pnpm verify`, server integration, e2e green.
- [ ] Real run with the flag on and a reachable server: create a 7-day link from Android, open it in
      a private browser window on another device; the selected photos, videos and notes show, unselected
      ones do not; a video plays and seeks; no location anywhere;
      the downloaded JPEG has no EXIF (check with any EXIF viewer).
- [ ] The browser address bar no longer shows the `#…` part after loading; server access logs show
      the path `/s/<linkId>` only, never the secret.
- [ ] Revoke → the open viewer's next request fails with the revoked/expired message.
- [ ] `PUBLIC_LINK_PROXY=true`: downloads come from `/api/v1/public-links/…/content` (DevTools),
      not from the S3 host.
- [ ] DB check: `public_links` holds a 32-byte hash, never the token; no row contains the title.
