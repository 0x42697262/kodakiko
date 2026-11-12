# 14 — Thumbnails, originals cache, downloads, pins, eviction, network rules

Status: draft 1 (2026-11-11).

Goal: every thumbnail of every readable entry ends up on the device (D-09) — downloaded in the
background and on demand for visible tiles — stored as one copy per the device's thumbnail mode
(D-12); originals download when opened, stay in a size-capped LRU cache, and pinned entries keep
theirs offline; uploads and prefetches follow the network rules of D-61. Wraps the local-only
sources of specs 11 and 13 without changing their callers.

Read first: `docs/design/storage.md` §§4, 6; `docs/design/data-model.md` §§4.1-4.2;
`docs/design/media.md` §3; specs 08 (uploads), 11 (`ThumbnailSource`), 13 (`OriginalSource`);
facts F-LIB-09, F-LIB-20; decisions D-09, D-12, D-61. Depends on 08, 09, 11, 13.

## 1. Packages and settings

Add `expo-network` (allowlist) to `apps/client`.

Device settings are spec 09 §4.2 (`encryptThumbnails`, `originalCacheCapBytes`,
`largeUploadsOnWifiOnly` are used here).

## 2. Network port (`src/adapters/network/`)

```ts
export interface NetworkInfo {
  readonly isWifi: () => Promise<boolean>;
  readonly subscribe: (listener: () => void) => () => void;
}
```

Native: `Network.getNetworkStateAsync()` → `type === NetworkStateType.WIFI || type ===
NetworkStateType.ETHERNET`; `subscribe` via `Network.addNetworkStateListener`. Web: `isWifi`
always `true`; `subscribe` on the `online` event.

## 3. FileStore additions (07a interface)

```ts
readonly downloadObject: (objectId: string, urlFactory: () => Promise<string>, expectedSize: number, onProgress: (bytes: number) => void, signal: AbortSignal) => Promise<void>;
```

- Native: delete any existing `<objectId>.kdks.partial`, then `File.createDownloadTask(url,
  partialFile, { onProgress })` (F-LIB-20, F-LIB-21) into it; `signal` → `task.cancel()`; after completion the size must equal
  `expectedSize` (else delete, throw `DownloadSizeMismatchError`); rename to the final name. Large
  originals never pass through JS memory.
- Web: `fetch(url, { signal })`, stream `response.body` chunk by chunk into an OPFS
  `createWritable()` on the `.partial` file, counting bytes; same size check and rename.
- Both: timeout `DOWNLOAD_TIMEOUT_MS = 600_000` per object via the signal; network errors and 5xx
  retried 3 times (2 s, 8 s, 30 s); every attempt calls `urlFactory()` for a fresh presigned URL
  (`GET /objects/{id}/download`, supplied by the downloader in §4). On Android every failure of
  the download task (`UnableToDownloadException("HTTP <code>")`, F-LIB-21) counts as retryable
  because a fresh URL fixes the common case (expired link); cancellation is not retried.

## 4. Downloader (`src/services/downloads/downloader.ts`)

```ts
export type DownloadPriority = 'visible' | 'opened' | 'background';
export interface Downloader {
  readonly fetchObject: (objectId: string, priority: DownloadPriority) => Promise<void>;
  readonly cancelBackground: () => void;
  readonly onCompleted: (listener: (objectId: string) => void) => () => void;
  readonly onProgress: (objectId: string, listener: (receivedBytes: number, totalBytes: number) => void) => () => void;
}
```

- One queue per priority; `opened` > `visible` > `background`; concurrency
  `THUMBNAIL_FETCH_CONCURRENCY = 6` for thumbnails (any priority), `ORIGINAL_FETCH_CONCURRENCY = 2`
  for originals. Same object requested twice → one download, both promises resolve.
- Per object: `fileStore.downloadObject(objectId, () => api download URL, sizeBytes, …)` → object row
  `localState 'present'`, `lastAccessAt = now` → emit `onCompleted`.
- `404` from the API (object replaced or entry deleted) → set the object row `localState
  'unavailable'` and stop (no retry). `ThumbnailSource.get`, `prefetchAll` and `open` skip
  `unavailable` objects; the next pull that brings a replacement object row resets the state.
- Background downloads pause while `syncService.status().offline` is true and resume on the next
  `onPassCompleted`.
- Lock/sign-out → `cancelBackground()` and abort all signals.

## 5. Thumbnail cache service (`src/services/thumbnails/thumbnail-cache.ts`)

Wraps spec 11's local source: `createCachingThumbnailSource({ local, downloader, … })`
implementing `ThumbnailSource`:
- `get(entryId, mediaId)`: local result → return it; else look up the media's current thumbnail
  object row; none uploaded yet → `null` (placeholder); else `downloader.fetchObject(id,
  'visible')`, then post-process (below) and return the local result.
- Post-process after a thumbnail download, by mode:
  - plain mode (Android default): decrypt → `fileStore.writePlainThumbnail(mediaId, bytes)` →
    delete the encrypted file → object row `localState 'missing'` (one copy, D-12);
  - encrypted mode / web: keep the encrypted object.
- `afterUpload(objectId)`: called by spec 08's upload coordination after a **thumbnail** object
  is confirmed; in plain mode deletes the local encrypted copy (the plain file already exists from
  spec 09) and sets `localState 'missing'`. (Change to spec 08 §3.6, applied there: "after confirm
  of a thumbnail object call `thumbnailCache.afterUpload(objectId)`".)
- `prefetchAll()`: after every pass (`syncService.onPassCompleted`, spec 08 §3.9), enqueue `background` downloads for every
  uploaded thumbnail object of a readable, non-trashed entry that has neither a present encrypted
  object nor a plain file. Thumbnails download on any network (D-61).
- `switchMode(encrypt: boolean)` (from settings, Android):
  - plain → encrypted: delete every plain thumbnail; mark the thumbnail objects `missing`;
    `prefetchAll()` downloads the encrypted objects again (the local copy is always the server's
    exact ciphertext, D-37).
  - encrypted → plain: for each present encrypted thumbnail: decrypt → write plain → delete the
    encrypted file → `missing`. Runs in the background, `INDEX_BUILD_YIELD_EVERY`-style yielding.
  - Progress via `thumbnailCache.modeSwitch()`: an observable `{ running: boolean; done: number;
    total: number }` with `subscribe`; the settings screen (spec 20) shows "Updating thumbnails…
    (done)/(total)".
- Thumbnails are never evicted.

## 6. Originals service (`src/services/originals/originals-service.ts`)

Wraps spec 13's local source as `createDownloadingOriginalSource({ local, downloader, … })`:
- `open(entryId, mediaId)`: local → return; `'missing'` → `downloader.fetchObject(id, 'opened')`
  with progress exposed through `useOriginalDownload(objectId)` (bytes / size); then local again.
  The viewer (spec 13) replaces its "Showing preview" label with a progress ring and "Downloading…"
  and swaps to the original on completion; failure → "Couldn't download the original. Showing
  preview." with "Retry".
- Pins (D-09): `pinEntry(entryId)`, `unpinEntry(entryId)`, `isPinned(entryId)` on the `pins` table;
  pinning enqueues `background` downloads of the entry's originals **only on Wi-Fi** (D-61; re-tried
  when `NetworkInfo` reports Wi-Fi). The viewer's overflow menu gets "Keep Offline" / "Remove from
  Offline" (owner and viewer roles both).
- Eviction `enforceCap()`: when the total size of present original objects exceeds
  `originalCacheCapBytes`, delete the least recently accessed first, skipping pinned entries'
  objects, objects with `uploadState !== 'uploaded'`, and objects currently open in the viewer;
  set `localState 'missing'`. Runs after every original download, on unlock and after a cap change.
  Cap `0` means **no limit** (guidelines 4.3 convention); `enforceCap` then does nothing.
- `usage()`: `{ originalsBytes, thumbnailsBytes, pinnedBytes, pendingUploadBytes }` from object rows
  and `listSizes` (spec 20 shows it); `clearOriginals()` deletes every present original that is not
  pinned, not pending upload and not open (the same skip rules), independent of the cap.

## 7. Upload network rule (D-61)

Spec 08's upload coordination gets a policy dependency `uploadPolicy.allowed(sizeBytes):
Promise<boolean>` = `sizeBytes <= deviceSettings.get().largeUploadThresholdBytes` (default 100 MiB, D-61) or
`!largeUploadsOnWifiOnly` or
`await network.isWifi()`. Objects not allowed stay `queued` with `uploadState 'queued'` and are
retried when `NetworkInfo.subscribe` fires or on the next pass; the sync banner shows "Waiting for
Wi-Fi to upload (n) large items." (text key `sync.waitingForWifi`) when that is the only thing
pending. (Change to spec 08 §3.6, applied there.)

## 8. Tests

Node, memory adapters, fake API/bucket (fake fetch serving ciphertext by object id), wrappers
Sodium:

1. Thumbnail miss → one download even when requested by 3 tiles at once; plain mode leaves exactly
   one plain file and no encrypted file; encrypted mode leaves the encrypted object.
2. `afterUpload` in plain mode deletes the encrypted local copy; in encrypted mode keeps it.
3. `prefetchAll` downloads only missing thumbnails of readable, non-trashed entries.
4. `switchMode` both directions end with exactly one copy per thumbnail of the right kind.
5. Download size mismatch → file deleted, object stays `missing`, error surfaced; 404 → `missing`
   without retry; 503 twice then 200 → success with three URL requests.
6. Originals: open missing → downloaded → decrypts to the original bytes; eviction with cap 10 MB
   over 15 MB of originals deletes the oldest unpinned uploaded ones only; pinned survive; a
   pending upload survives.
7. Upload policy: 120 MiB object with the default threshold and the setting on and `isWifi false` → stays queued; network
   listener fires with Wi-Fi → uploads.
8. Device settings schema: defaults per platform; invalid update rejected.

## 9. Verification checklist

- [ ] `pnpm verify` and e2e green.
- [ ] Fresh install on a second Android device, sign in to an account with 200 entries → gallery
      tiles fill in within a minute on Wi-Fi; after that, airplane mode → every tile still shows.
- [ ] Open a photo whose original is not local → progress, then full resolution.
- [ ] "Clear Downloaded Originals" (dev toggle until spec 20) → originals cleared (usage shows 0
      except pinned and pending); a smaller cap evicts the oldest first; pinned entry still
      opens offline.
- [ ] Switch "Encrypt thumbnails on this device" on and off (via a temporary dev toggle until spec
      20) → gallery keeps working; `thumbs-plain/` empty when on.
- [ ] Record a video larger than 100 MiB on mobile data with the Wi-Fi rule on → banner says it
      waits; switch to Wi-Fi → it uploads.
- [ ] `adb shell run-as com.x42697262.kodakiko ls files/…/thumbs-plain` shows WebP files only in plain
      mode; no file under the app directory contains a plaintext original after closing the viewer.
