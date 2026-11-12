# Media

Status: draft 1 (2026-11-11). Normative for the capture and import paths in
`apps/client/src/features/capture/`, the thumbnail pipeline in
`apps/client/src/services/media/`, and EXIF handling in `packages/core/src/media/`.

Covers: capture (camera), import (picker, drag-drop, paste), thumbnails, EXIF and time extraction,
video specifics, and the strip-for-public-link pipeline (D-25).

## 1. Sources of media (D-20)

| Source | Android | Web | Produces |
|---|---|---|---|
| Camera | `react-native-vision-camera` (F-LIB-06): tap = photo, hold = video, front/back switch (D-15) | `getUserMedia` + `MediaRecorder` (video) and a canvas capture (photo) | a plaintext temp file in `tmp/` |
| System picker | `expo-image-picker` → Android Photo Picker, multi-select (F-LIB-12) | `<input type="file" multiple>` | file references |
| Drag and drop | n/a | `drop` event → `DataTransfer.files` | file references |
| Clipboard paste | n/a | `paste` event → `clipboardData.files` (D-20) | file references |

All sources funnel into one import pipeline (section 2). The camera writes to `tmp/`; picker/drop/
paste give the app a file it copies into `tmp/` before processing, so every downstream step reads
from one place and the plaintext original is deleted after encryption (`sync.md` §5.1 step 3).

Capture limits (server config, `api.md` §11 `PublicConfigDto`): `maxMediaPerEntry` (default 20,
hard max 100) and `maxUploadBytes` per file (D-19). The client enforces them before encrypting and
shows a plain refusal message when exceeded (guidelines 3.2 step 3: validate the payload before any
outbound request).

## 2. Import pipeline (per media item)

Orchestration in `apps/client/src/services/media/import-service.ts` (spec 09 §4); each step is an
adapter call.

1. **Sniff kind and MIME.** From the file's magic bytes, not the extension
   (`packages/core/src/media/sniff.ts`, a frozen table of signatures). Unsupported kinds are
   rejected before any encryption. Allowed: JPEG, PNG, WebP, HEIC/HEIF, MP4 (H.264/HEVC),
   WebM (VP8/VP9/AV1), MOV (H.264/HEVC). The table lives in one place; adding a format is a data
   change (guidelines 7.3 evolvability).
   **Web decodability check:** the web app can only make thumbnails for formats the browser
   decodes. Before accepting a file on web, the pipeline loads it into an `Image` (photo) or a
   `<video>` element (video, `loadeddata` event) with a `MEDIA_PROBE_TIMEOUT_MS` (10 s) limit.
   Failure (typically HEIC/HEIF photos or HEVC videos outside Safari) refuses the file with
   "This browser can't read <file name>. Add it from the Android app instead." No placeholder
   thumbnails exist (guidelines 8: nothing pretends to work).
2. **Read dimensions and duration.** Android: photo width/height from
   `expo-image` `Image.loadAsync(uri)` (returns an image reference with dimensions); video
   duration and size from an `expo-video` player's metadata on load. Web: `naturalWidth`/`naturalHeight`
   of the probe `Image`, `videoWidth`/`videoHeight`/`duration` of the probe `<video>`. EXIF
   orientation 5-8 swaps width and height so the metadata holds display dimensions.
3. **Extract capture time and location** (section 4).
4. **Generate the thumbnail** (section 3).
5. **Encrypt** the original and the thumbnail into the FileStore as STREAM objects
   (`crypto.md` §7); delete the plaintext temp file.
6. **Build the `MediaDescriptorV1`** (`data-model.md` §6.1) with the derived fields and the
   plaintext byte sizes (needed to compute STREAM object sizes and to verify on download).

Steps 1-4 run before any network or encryption, so a bad file is rejected cheaply and locally
(guidelines 3.2).

## 3. Thumbnails

| Property | Value |
|---|---|
| Format | WebP (lossy), `image/webp` |
| Max edge | `THUMBNAIL_MAX_EDGE` = 512 px (client constant) |
| Quality | `THUMBNAIL_QUALITY` = 80 (client constant) |
| Video poster | the frame at `min(1 s, duration/2)`, same encoding |

Generation:

- **Android photo**: `expo-image-manipulator` (allowlisted): resize so the longest edge is
  `THUMBNAIL_MAX_EDGE`, save as `SaveFormat.WEBP` with `compress = THUMBNAIL_QUALITY / 100`. The
  manipulator applies EXIF orientation. The resulting temp file is encrypted into the FileStore
  and, in default mode, moved to `thumbs-plain/` after the encrypted copy is uploaded (D-12,
  `data-model.md` §4.2).
- **Android video poster**: `expo-video-thumbnails` (allowlisted) `getThumbnailAsync(uri, { time })`
  at the poster time, then the same manipulator resize/WebP step.
- **Web photo**: `createImageBitmap(file, { imageOrientation: 'from-image' })`, draw scaled to an
  `OffscreenCanvas`, `convertToBlob({ type: 'image/webp', quality })` on the main thread; the import
  loop yields between chunks so the page stays responsive (spec 09 §4).
- **Web video poster**: seek the probe `<video>` to the poster time, draw the frame to a canvas,
  same WebP encode (main thread; video elements cannot live in a worker).

Thumbnails are encrypted with the `thumbnailKey` variant (`crypto.md` §4), separate from the
original's key, so a thumbnail-only cache leak does not expose originals.

The gallery loads thumbnails through the cache (`storage.md` §6): present → decode/display; missing
→ download the thumbnail object, decrypt in memory, display, and cache. Concurrency is bounded
(`THUMBNAIL_FETCH_CONCURRENCY`). On Android default mode the plain file is loaded by URI directly
(F-LIB-09); in encrypted mode and on web the decrypted bytes become a data URI (Android) or object
URL (web) held only in memory.

## 4. EXIF, capture time and location (D-25)

Originals are stored **untouched**: the full EXIF (including GPS) stays inside the encrypted
original object. Nothing is stripped at capture or import. Extraction reads a copy of the header
bytes only, never the whole file.

### 4.1 Time

`packages/core/src/media/capture-time.ts`, pure:

1. Prefer EXIF `DateTimeOriginal` (photo) or the video creation time (MP4/MOV `mvhd`; WebM files
   carry none in v1, D-56), interpreted with the EXIF `OffsetTimeOriginal` when present, else as local time with
   the device's current UTC offset.
2. Else prefer the file system mtime.
3. Else (pasted images usually carry none, D-20) use `now`.
4. The chosen value becomes `capturedAtUtc` on the media descriptor; the entry's `takenAtUtc` is
   the **earliest** `capturedAtUtc` across its media, and `utcOffsetMinutes` is the offset in
   effect at that time (`data-model.md` §6.1, D-45). The user can edit the entry time; editing it
   does not rewrite the media EXIF.

EXIF parsing library: **not `exifr`** (stale, 2021, React Native undocumented, F-LIB-15). The spec
pins a maintained parser or a small in-repo EXIF/TIFF header reader in `packages/core/src/media/exif.ts`
that extracts only `DateTimeOriginal`, `OffsetTimeOriginal`, GPS lat/long and orientation. The
choice is recorded in `docs/dependencies.md` before the capture feature is built; a hand-rolled
reader is acceptable here because it parses a documented container format and does no crypto
(guidelines: the "no hand-made crypto" rule does not extend to file-format parsing).

### 4.2 Location

GPS (EXIF GPS for photos, `mvhd`/`udta` for videos where present, or, for **camera captures only** and only when the user turned on "Add location to new photos"
(D-55), the device location) becomes
`EntryMetadata.location = { latitude, longitude, accuracyMeters, source }` with `source` `'exif'`
or `'device'` (`data-model.md` §6.1). Location is encrypted inside the entry metadata; the server
never sees it (`architecture.md` §2).

Display: coordinates with an "Open in Maps" button that hands `geo:lat,lng` to the OS (Android) or
a maps URL (web). **No in-app map in v1** (D-25): map tiles would reveal the area to the tile
provider, defeating E2EE. An in-app map would require offline encrypted tiles, a much larger
feature, deferred.

## 5. Video specifics

- Videos are stored **as recorded**: no transcoding on device (slow, battery). The container and
  codec are whatever the camera produced (`storage.md` §1 sniff table).
- Playback: web via the service-worker range decrypt (D-32, `storage.md` §5); Android via
  decrypt-to-temp-file. HEVC playback depends on the device/browser codec support; when a codec
  cannot play, the player shows "This video's format can't be played here. Try the Android app."
  (spec 15 §5) instead of a broken player, and this is documented as a limitation, not hidden (guidelines 8: no client-only behavior that pretends to work).
- No length cap (D-19). The only bounds are `maxUploadBytes` per file and the user quota. The
  STREAM format and multipart upload keep memory constant regardless of length (`crypto.md` §7,
  `storage.md` §3).

## 6. Strip-for-public-link (D-25, D-48)

A public link never serves an original. When the owner creates a link (`api.md` §9), the client
builds, for each shared entry, a `link_copy`:

1. For each media item, produce a **stripped** derivative:
   - photo: re-encode to a new JPEG/WebP at `LINK_PHOTO_MAX_EDGE` = 1600 px as JPEG quality 85 (D-65),
     with **all metadata removed** (a fresh encode through `expo-image-manipulator` on Android
     or canvas on web; that these outputs carry no EXIF/GPS is UNVERIFIED for the manipulator,
     so the pipeline runs the in-repo EXIF reader on every stripped output and refuses to
     publish if any EXIF block remains);
   - video: container metadata removed **without re-encoding** (D-65): MP4/MOV through the in-repo
     ISO-BMFF rewriter (drops `udta`/`meta`/`uuid` boxes, zeroes creation and modification times,
     fixes chunk offsets), WebM with its `DateUTC` cleared; outputs verified by the readers
     (spec 18 §2.3). The owner selects exactly which photos and videos are included.
2. Encrypt each stripped derivative as a new STREAM object under a **fresh media key** in the link
   collection (not the original's key), so revoking the link and deleting its objects cannot touch
   the originals.
3. The `link_copy` entry metadata carries the stripped media descriptors and **no location**
   (`location: null`) and the note **only if** the owner opted to include it (default: include the
   note text, exclude tags and location). The strip removes GPS from pixels and metadata alike.

The owner's gallery never shows `link_copy` entries (`purpose = 'link_copy'`, `data-model.md` §2.6).
Revoking the link deletes the `link` collection, its copies and their objects
(`storage.md` §7); originals are untouched.

## 7. Editing and deleting (D-18)

- **Edit**: the note (markdown, plain text + preview toggle), tags, entry time and location are
  editable any time. Editing metadata is a `PATCH /entries/{id}` (`api.md` §6); it never re-encrypts
  media. Reordering media rewrites the `media` array order in the metadata.
- **Delete**: from the gallery (multi-select, D-23) or an entry. Delete moves the entry to trash
  (`trashed_at`); after 30 days the `purge-trash` job permanently deletes it and moves its objects
  to `deleting` (`data-model.md` §5). The trash is a server-side flag, so a deleted entry syncs as
  deleted to every device; restore clears the flag before the purge.
- Entries have an undo path through the 30-day trash (guidelines 7.1). Removing one photo or video
  from an entry is final after sync (its objects are deleted after `OBJECT_GRACE_DAYS`, D-34); the
  edit screen says so ("Removed photos and videos can't be restored.").
