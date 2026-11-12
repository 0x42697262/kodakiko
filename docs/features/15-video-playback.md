# 15 — Video playback (web service worker, Android temp file)

Status: draft 1 (2026-11-11).

Goal: tapping a video plays it. Web streams it through the media service worker, which fetches
only the needed ciphertext chunks with HTTP Range requests, decrypts them and answers the
`<video>` element's range requests (instant start, seeking, constant memory, D-32). Android
decrypts the original to a temporary file and plays it. Replaces spec 13's placeholder
`VideoLauncher`.

Read first: `docs/design/storage.md` §5 (normative for the worker), `docs/design/crypto.md` §§7,
9.2; `docs/design/media.md` §5; specs 13 (`VideoLauncher`, `OriginalSource`), 14 (downloads);
decisions D-32, D-38, D-62. Depends on 05 (download URLs, Range) and 14.

## 1. Player component (both platforms)

`src/features/video/video-player-screen.tsx`, route `/play/[entryId]/[mediaId]` (modal,
full-screen, black background): `expo-video` `useVideoPlayer(source, player => { player.loop =
false; player.play(); })` + `<VideoView player nativeControls contentFit="contain" fullscreenOptions={{ enable: true }} />` (F-LIB-19; `VideoView` has no `allowsFullscreen` prop).
`source` comes from `VideoSource.prepare` (§2); while preparing, a progress view ("Preparing video…
(n)%" on Android; "Loading…" on web). Close button (and Esc / hardware back) → `release()` the
source, back to the entry. Player errors → message by cause (§5). `VideoLauncher.canPlay()` is now
`true`; `open(entryId, mediaId)` navigates to the route. While the player reports playing, the screen calls `activity.touch()` every
`ACTIVITY_THROTTLE_MS` (spec 16 §2; until spec 16 the activity source is a no-op).

## 2. `VideoSource` port (`src/adapters/video-source/`)

```ts
export type PreparedVideo = { readonly uri: string; readonly release: () => Promise<void> };
export interface VideoSource {
  readonly prepare: (input: { readonly entryId: string; readonly mediaId: string; readonly onProgress: (fraction: number) => void; readonly signal: AbortSignal }) => Promise<PreparedVideo>;
  readonly releaseAll: () => Promise<void>;
}
```

Shared inputs resolved by a helper `resolveVideoInputs(entryId, mediaId)`: the original object row
(id, `sizeBytes`, `plaintextSize`), the media's `mimeType` from the metadata, and the original
variant key (entry key → media key → `kdfDerive(…, mediaOriginal)`).

### 2.1 Android (`video-source.native.ts`)

1. Original not present locally → `downloader.fetchObject(id, 'opened')` (spec 14) with progress
   mapped to the first half of `onProgress`.
2. Free space check: `Paths.availableDiskSpace` (F-LIB-21) must exceed `plaintextSize +
   VIDEO_FREE_SPACE_MARGIN_BYTES (104_857_600)`, else `NotEnoughSpaceError`.
3. Decrypt chunk by chunk with `createStreamDecryptor` from the FileStore object into
   `TempFiles.create('play', ext)` (spec 09 §4.1; ext from MIME: `mp4`, `mov`, `webm`);
   progress maps to the second half; the `signal` aborts and deletes the partial file; `finish()`
   must pass (truncation check).
4. Return `{ uri: 'file://…', release }`; `release` deletes the file.
5. `releaseAll` (on lock, sign-out and app start) deletes every `tmp/play-*` file.

### 2.2 Web (`video-source.web.ts`)

1. `mediaWorker.isAvailable()` (§3.4) true:
   - `playbackId` = 16 random bytes base64url;
   - post `register` (§3.2) with the variant key, `objectId`, a fresh download URL (`GET
     /objects/{id}/download`), `plaintextSize`, `ciphertextSize`, `mimeType`;
   - return `{ uri: \`/__kdks_media/${playbackId}\`, release }`; `release` posts `forget`.
   - The original is **not** downloaded to OPFS for streaming (no local copy needed); if the
     original is already present locally, the worker still streams from the bucket (simplicity;
     OPFS is not accessible to the worker's fetch path in v1).
2. Not available (fallback, D-62): `plaintextSize ≤ WEB_BLOB_PLAYBACK_MAX_BYTES (209_715_200)` →
   download if missing (spec 14), decrypt into a `Blob` (chunks collected, `type: mimeType`),
   object URL; else throw `StreamingUnavailableError`.

## 3. Media service worker (`apps/client/web/sw/`)

### 3.1 Build and registration

- Source `web/sw/media-sw.ts`; esbuild bundle (`format: 'iife'`, `platform: 'browser'`, `minify:
  true`) to `public/media-sw.js`, added to the `build:workers` script (spec 07a §1).
- Bundled code: imports only from `@kodakiko/core` (its public barrel: stream functions, constants,
  byte helpers, errors) and `createWrappersSodium(libsodium-wrappers-sumo, argon2id: throwing stub)`
  (spec 07a §4.1); esbuild tree-shakes the rest. No other imports.
- Registration (web app start, `src/adapters/video-source/media-worker.web.ts`):
  `navigator.serviceWorker.register('/media-sw.js', { scope: '/' })`; then await
  `navigator.serviceWorker.ready` and, if `navigator.serviceWorker.controller` is null, wait for
  `controllerchange` (up to `SW_CONTROL_TIMEOUT_MS = 5000`). The worker calls `self.skipWaiting()` in
  `install` and `self.clients.claim()` in `activate` (`storage.md` §5).
- Caddy (spec 21) serves `/media-sw.js` with `Cache-Control: no-cache` and
  `Service-Worker-Allowed` is not needed (scope `/` equals the script's directory).

### 3.2 Messages

Page → worker (`navigator.serviceWorker.controller.postMessage`):

| Type | Fields |
|---|---|
| `register` | `playbackId`, `key` (ArrayBuffer, transferred), `objectId`, `downloadUrl`, `extraHeaders` (record of header name → value or `null`; used by spec 18's proxy mode, where `downloadUrl` is a same-origin API path), `plaintextSize`, `ciphertextSize`, `mimeType` |
| `refreshUrl` | `playbackId`, `downloadUrl` |
| `forget` | `playbackId` |
| `forgetAll` | — (sent on lock and sign-out) |
| `ping` | `nonce` → worker replies `{ type: 'pong', nonce }` through `event.source.postMessage` |

Worker → page (`client.postMessage` to the requesting client, `event.clientId`):

| Type | Fields |
|---|---|
| `needKey` | `playbackId` (the worker restarted and lost its state) |
| `needUrl` | `playbackId` (the bucket answered 403: URL expired) |

The page's `media-worker.web.ts` listens for these and answers with `register` / `refreshUrl`
using the still-open `PreparedVideo` state; unknown playback ids are ignored. The worker waits for an
answer up to `SW_KEY_REQUEST_TIMEOUT_MS = 5000`.

Keys live only in a `Map<playbackId, Playback>` in worker memory; `forget` wipes the key bytes.

### 3.3 Fetch handling

`self.addEventListener('fetch', event => …)`:
1. Path not starting with `/__kdks_media/` → return (no `respondWith`).
2. Look up the playback (missing → `needKey` round trip; still missing → `410`).
3. On first use of a playback: fetch the 24-byte header (`Range: bytes=0-23`), `parseStreamHeader`,
   layout from `plaintextSize` and the header exponent; check `ciphertextSize` matches the layout
   (else `502`); fetch and decrypt the **final** chunk with `isFinal = true` once (truncation check,
   `crypto.md` §7.2; failure → `502`). Cache header and layout in the playback.
4. Parse the `Range` header: `bytes=start-end`, `bytes=start-`, or none (= `bytes=0-`); a suffix
   range `bytes=-n` → last `n` bytes; unsatisfiable → `416` with `Content-Range: bytes
   */plaintextSize`.
5. Clamp `end` so at most `SW_MAX_RESPONSE_CHUNKS = 4` chunks are decrypted per response
   (`storage.md` §5).
6. `chunkRangeForPlaintextRange` → ciphertext byte range `[24 + first × (C + 16), 24 + (last + 1) ×
   (C + 16) − 1]` clamped to `ciphertextSize − 1`; one bucket fetch with that `Range`; `403` →
   `needUrl` round trip and one retry; other errors → `502`.
7. Split the body into chunks, `decryptChunk` each with `isFinal = (index === chunkCount − 1)`,
   concatenate, slice to `[start, end]`.
8. Respond `206` with `Content-Type: mimeType`, `Content-Range: bytes start-end/plaintextSize`,
   `Content-Length`, `Accept-Ranges: bytes`, `Cache-Control: no-store`.
9. Decryption failure → `502` and the page shows the "damaged" message (§5).

### 3.4 Availability check

`mediaWorker.isAvailable()`: `'serviceWorker' in navigator`, registration succeeded, a controller
exists, and a `ping` gets its `pong` within `SW_PING_TIMEOUT_MS = 2000`. Cached for the page
lifetime.

## 4. Lifecycle

- Lock / sign-out: `videoSource.releaseAll()` (web: `forgetAll`; Android: delete `tmp/play-*`).
- Android app start: delete leftover `tmp/play-*` (crash recovery).
- Leaving the player route always calls `release`.

## 5. Errors and messages

| Cause | Message |
|---|---|
| `NotEnoughSpaceError` | `Not enough free space to play this video.` |
| `StreamingUnavailableError` | `This browser can't stream encrypted videos. Open it in Chrome or the Android app.` |
| player `error` event with a decode/format cause | `This video's format can't be played here. Try the Android app.` |
| `502` / `DecryptionError` / truncation | `This video can't be opened. It may be damaged.` |
| download failure | `Couldn't download the video. Check your connection and try again.` + Retry |
| `410` (worker lost its key and the page did not answer) | `Playback stopped. Tap to retry.` (tap → `prepare` again) |

## 6. Tests

1. Range logic extracted into `web/sw/range.ts` (pure): parse/clamp table (no header, open-ended,
   suffix, beyond end, multi-range `bytes=0-1,5-6` → treated as the first range), ciphertext range
   math for chunk boundaries, last partial chunk, a 1-byte video. Node tests.
2. Worker fetch handler in Node: run `media-sw.ts`'s handler function (exported for tests as
   `handleMediaRequest(request, state, deps)` with an injected `fetch` serving a real STREAM object
   built with core) → responses for `bytes=0-`, `bytes=1048570-1048590` (crosses a chunk boundary),
   and the last byte equal the plaintext slices; a truncated object (last chunk removed, size
   adjusted) → `502`; an expired URL (fake 403 once) → `needUrl` then success.
3. Android source logic with a fake FileStore and a fake file sink (Node): decrypts to the original
   bytes; abort deletes the partial file.

## 7. Verification checklist

- [ ] `pnpm verify` green.
- [ ] Chrome desktop and Chrome on Android (web app): a 3-minute 1080p video starts in under 2 s on
      a LAN, seeking to the middle and the end works, DevTools Network shows `206` responses from
      `/__kdks_media/…` and ranged requests to the bucket of ~4 MiB, not one large download.
- [ ] Reload the page during playback → playback resumes after the `needKey` round trip (or report
      what happened).
- [ ] Leave a video paused 20 minutes (URL expiry is 15) → resume → plays (the `needUrl` path).
- [ ] Firefox: works or falls back; Safari: report behavior (UNVERIFIED in the design).
- [ ] Android: a 200 MB video shows "Preparing video…" progress, then plays; after closing, no
      `tmp/play-*` file remains; lock during playback removes it too.
- [ ] Lock on web during playback → playback stops on the next range request (`410`) and the key
      is gone (the worker's map is empty, checked with a debug message in development).
