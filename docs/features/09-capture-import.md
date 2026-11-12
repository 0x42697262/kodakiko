# 09 — Capture and import pipeline

Status: draft 1 (2026-11-11).

Goal: the user can take photos (tap) and videos (hold) with the front or back camera, add existing
photos/videos (Android Photo Picker; web file picker, drag and drop, paste), and every item is
turned into an **encrypted, ready-to-save media item** (original + thumbnail as STREAM objects in
the FileStore, a `MediaDescriptorV1`, its time and location) collected in a capture tray. Saving
the tray as an entry is spec 10. Plaintext never outlives the import of an item.

Read first: `docs/design/media.md` §§1-5; `docs/design/crypto.md` §§4, 7; `docs/design/data-model.md`
§§4, 6.1; `docs/design/client.md` §§1-2; spec 07a (ports, conventions); facts F-LIB-05/06/09/12,
F-LIB-15; decisions D-15, D-19, D-20, D-25, D-55, D-56. Depends on 07b (account state, config service) and 08 (memory FileStore for tests).

Library facts this spec relies on (`F-LIB-18` in `docs/facts.md`, checked 2026-11-11 against the
`react-native-vision-camera` main branch source):

- `<Camera style isActive device outputs={[photoOutput, videoOutput]} />`,
  `useCameraDevice('back' | 'front')`, `usePhotoOutput(Partial<PhotoOutputOptions>)` with
  `containerFormat: 'jpeg' | 'heic' | 'dng' | 'native'` and `quality`,
  `useVideoOutput(Partial<VideoOutputOptions>)` with `enableAudio`, `fileType: 'mp4' | 'mov'`.
- `photoOutput.capturePhotoToFile(settings, callbacks)` → `{ filePath, width, height, orientation,
  … }`; `filePath` is a plain path (prepend `file://` for URIs).
- `videoOutput.createRecorder({ maxDuration?, maxFileSize? })` → `Recorder` with
  `startRecording(onFinished(filePath, reason), onError, onPaused?, onResumed?)`,
  `stopRecording()`, `cancelRecording()`, `recordedDuration`, `recordedFileSize`.
- `useCameraPermission()` / `useMicrophonePermission()` → `{ status, hasPermission,
  canRequestPermission, requestPermission(): Promise<boolean> }`.

## 1. Packages and app config

Add to `apps/client` (all on the allowlist): `react-native-vision-camera`,
`react-native-nitro-modules`, `react-native-nitro-image`, `expo-image-picker`,
`expo-image-manipulator`, `expo-video`, `expo-video-thumbnails`, `expo-location`.

`app.config.ts` changes: `android.permissions` += `android.permission.CAMERA`,
`android.permission.RECORD_AUDIO`; plugin `expo-location` with
`locationWhenInUsePermission: "Kodakiko adds your location to new photos only if you turn this on in
Settings."` and `isAndroidBackgroundLocationEnabled: false` (it adds the coarse/fine permissions);
**Do not** add the `expo-image-picker` config plugin: with `cameraPermission: false` or
`microphonePermission: false` it **blocks** `CAMERA` / `RECORD_AUDIO` for the whole app (it calls
`withBlockedPermissions`, F-LIB-19), which would break vision-camera; the Android Photo Picker
needs no permission, so the plugin is not needed at all. `prebuild:android` must be re-run, then
check the merged manifest contains `CAMERA` and `RECORD_AUDIO` and does not mark them removed.

## 2. Core media modules (`packages/core/src/media/`)

All pure, bounded, and tolerant: malformed input never throws out of a reader; it returns empty
results with `malformed: true`. Every offset is bounds-checked before reading. Numbers live in
`media/constants.ts`.

### 2.1 `byte-source.ts`

```ts
export type ByteSource = { readonly size: number; readonly read: (offset: number, length: number) => Promise<Uint8Array> };
export function byteSourceFromBytes(bytes: Uint8Array): ByteSource;
```

`read` past the end returns the available bytes (possibly empty). Platform adapters implement it
over files (Android `FileHandle`) and `Blob.slice` (web).

### 2.2 `sniff.ts`

```ts
export type MediaFormat = { readonly kind: 'photo' | 'video'; readonly mimeType: string; readonly container: 'jpeg' | 'png' | 'webp' | 'heif' | 'mp4' | 'mov' | 'webm' };
export function sniffFormat(header: Uint8Array): MediaFormat | null;
```

Frozen signature table (`SIGNATURES`), checked against the first `SNIFF_HEADER_BYTES = 64` bytes:

| Container | Rule | MIME |
|---|---|---|
| jpeg | bytes 0-2 = `FF D8 FF` | `image/jpeg` |
| png | bytes 0-7 = `89 50 4E 47 0D 0A 1A 0A` | `image/png` |
| webp | bytes 0-3 = `RIFF` and 8-11 = `WEBP` | `image/webp` |
| heif | bytes 4-7 = `ftyp` and major brand (8-11) or any compatible brand in the ftyp box ∈ {`heic`, `heix`, `heim`, `heis`, `hevc`, `hevx`, `mif1`, `msf1`} | `image/heic` for `heic/heix/heim/heis`, else `image/heif` |
| mov | `ftyp` with major brand `qt  ` | `video/quicktime` |
| mp4 | `ftyp` with major brand ∈ {`isom`, `iso2`, `iso4`, `iso5`, `iso6`, `mp41`, `mp42`, `avc1`, `M4V `, `dash`, `3gp4`, `3gp5`, `3g2a`} | `video/mp4` |
| webm | bytes 0-3 = `1A 45 DF A3` | `video/webm` |

HEIF brands are checked before MP4 brands (an `mif1` file is an image). Anything else → `null`
(refused: `media.md` §2 step 1).

### 2.3 `exif.ts`

```ts
export type ImageMetadata = { readonly dateTimeOriginal: string | null; readonly offsetTimeOriginal: string | null; readonly gps: GeoPoint | null; readonly orientation: number | null; readonly malformed: boolean };
export type GeoPoint = { readonly latitude: number; readonly longitude: number };
export async function readImageMetadata(source: ByteSource, container: 'jpeg' | 'png' | 'webp'): Promise<ImageMetadata>;
export function parseTiffExif(tiff: Uint8Array): ImageMetadata;
```

- Locating the TIFF block (reads at most `EXIF_SCAN_MAX_BYTES = 262_144` from the start):
  - JPEG: walk markers from offset 2 (`FF xx` + big-endian length); the first `APP1` (`FF E1`)
    whose payload starts with `Exif\0\0` → TIFF = rest of that payload; stop at `SOS` (`FF DA`).
  - PNG: walk chunks (length, type); `eXIf` → TIFF = chunk data.
  - WebP: walk RIFF chunks from offset 12; `EXIF` → TIFF = chunk data (skip a leading `Exif\0\0`
    if present).
- `parseTiffExif`: byte order `II` (little) or `MM` (big), magic 42, IFD0 offset; read IFD0 entries:
  `0x0112` Orientation (SHORT), `0x8769` Exif IFD pointer, `0x8825` GPS IFD pointer. Exif IFD:
  `0x9003` DateTimeOriginal (ASCII, 19 chars `YYYY:MM:DD HH:MM:SS`), `0x9011` OffsetTimeOriginal
  (ASCII `±HH:MM`). GPS IFD: `1` LatitudeRef (`N`/`S`), `2` Latitude (3 RATIONAL), `3`
  LongitudeRef (`E`/`W`), `4` Longitude. Degrees = d + m/60 + s/3600, negated for `S`/`W`; reject
  values outside ±90/±180 and the exact `0,0` point (common "no fix" placeholder). Follow at most
  `EXIF_MAX_IFD_ENTRIES = 512` entries per IFD and never revisit an offset (loop guard).
- Strings that do not match their format → `null` for that field.

### 2.4 `iso-bmff.ts` (MP4/MOV)

```ts
export type VideoMetadata = { readonly creationUtc: string | null; readonly location: GeoPoint | null; readonly malformed: boolean };
export async function readVideoMetadata(source: ByteSource): Promise<VideoMetadata>;
export function parseIso6709(text: string): GeoPoint | null;
```

- Walk top-level boxes (32-bit size + type; size 1 → 64-bit largesize; size 0 → to end) with at
  most `BOX_WALK_MAX_BOXES = 64` boxes, reading only 16-byte headers, until `moov`. Read `moov`
  (refuse if larger than `MOOV_MAX_BYTES = 8_388_608`).
- Inside `moov`: `mvhd` version 0 → 32-bit `creation_time` at offset 4 of the payload after
  version/flags; version 1 → 64-bit. Seconds since 1904-01-01T00:00:00Z
  (`MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800`); value 0 → `null`.
- `moov/udta/©xyz` (type bytes `A9 78 79 7A`): 2-byte length, 2-byte language, then ISO 6709 text
  like `+37.3861-122.0839/` or `+37.3861-122.0839+012.0/` → `parseIso6709`.
- `moov/meta` with `keys`/`ilst` key `com.apple.quicktime.location.ISO6709` → same parser (Android
  cameras use `©xyz`; iPhone-made MOV files use this).

### 2.5 `capture-time.ts`

```ts
export type TimeSource = 'exif' | 'container' | 'file' | 'now';
export type ResolvedTime = { readonly capturedAtUtc: string; readonly utcOffsetMinutes: number; readonly source: TimeSource };
export function resolveCaptureTime(input: { readonly exifDateTimeOriginal: string | null; readonly exifOffset: string | null; readonly containerCreationUtc: string | null; readonly fileModifiedUtc: string | null; readonly nowUtc: string; readonly deviceOffsetMinutes: number }): ResolvedTime;
export function earliestTime(times: readonly ResolvedTime[]): ResolvedTime;
export function deviceOffsetMinutes(date: Date): number;
```

Order (`media.md` §4.1): EXIF (with its offset, else the device offset) → container creation
(UTC; offset = device offset) → file modified time → now. `deviceOffsetMinutes(date) =
-date.getTimezoneOffset()`. `earliestTime` picks the smallest `capturedAtUtc`.

### 2.6 `geometry.ts`

`fitWithin(width, height, maxEdge): { width, height }` (keeps aspect ratio, never upscales, rounds
to integers ≥ 1); `displaySize(width, height, orientation)` swaps for EXIF orientations 5-8.

## 3. Client: media toolkit port (`src/adapters/media/`)

```ts
export type MediaInput = { readonly origin: 'camera' | 'picker' | 'drop' | 'paste'; readonly handle: PlatformMediaHandle; readonly fileName: string | null; readonly pickerExif: PickerExif | null };
export interface MediaToolkit {
  readonly byteSource: (input: MediaInput) => Promise<ByteSource>;
  readonly probe: (input: MediaInput, format: MediaFormat) => Promise<ProbeResult>;
  readonly makeThumbnail: (input: MediaInput, format: MediaFormat, posterTimeMs: number) => Promise<{ readonly bytes: Uint8Array; readonly width: number; readonly height: number }>;
  readonly modifiedUtc: (input: MediaInput) => Promise<string | null>;
  readonly discard: (input: MediaInput) => Promise<void>;
}
export type ProbeResult = { readonly width: number; readonly height: number; readonly durationMs: number | null; readonly decodable: boolean };
```

- `PlatformMediaHandle`: native `{ uri: string }` (a `file://` URI in the app cache); web `{ blob: Blob }`.
- `PickerExif`: the raw `exif` object from `expo-image-picker` (Android), parsed by
  `pickerExifToMetadata(exif): ImageMetadata` in `src/services/media/picker-exif.ts` (keys
  `DateTimeOriginal`, `OffsetTimeOriginal`, `GPSLatitude`/`GPSLatitudeRef`,
  `GPSLongitude`/`GPSLongitudeRef`, `Orientation`; numbers or `"d/1,m/1,s/100"` strings are both
  accepted; anything else → null).

`media-toolkit.native.ts`:
- `byteSource`: `new File(uri).open()` → `FileHandle` with `offset` + `readBytes` (F-LIB-05);
  size from `file.size`; handles closed by `discard`.
- `probe`: photo → `Image.loadAsync(uri)` (expo-image) width/height, `decodable: true`; video →
  `createVideoPlayer(uri)` (expo-video), wait for its `sourceLoad` event (timeout
  `MEDIA_PROBE_TIMEOUT_MS = 10_000`), whose payload has `duration` (seconds) and
  `availableVideoTracks[0].size` (`{ width, height }`); then `player.release()`.
  Failure → `decodable: false`.
- `makeThumbnail`: photo → `ImageManipulator.manipulate(uri).resize(fitWithin(...))` →
  `renderAsync()` → `saveAsync({ format: SaveFormat.WEBP, compress: quality / 100 })`; video →
  `getThumbnailAsync(uri, { time: posterTimeMs })` then the same manipulator step; read the WebP
  file bytes, delete the temp files. These calls match the SDK 58 type definitions (F-LIB-19):
  `ImageManipulator.manipulate(source)` → context with `resize({ width, height })` and
  `renderAsync(): Promise<ImageRef>`; `ImageRef.saveAsync({ format: SaveFormat.WEBP, compress })` →
  `{ uri, width, height }`; `getThumbnailAsync(uri, { time, quality })` → `{ uri, width, height }`.
- `modifiedUtc`: `File.modificationTime` if available, else `null`.
- `discard`: delete the cache file.

`media-toolkit.web.ts`:
- `byteSource`: `Blob.slice(offset, offset + length).arrayBuffer()`.
- `probe`: photo → `createImageBitmap(blob, { imageOrientation: 'from-image' })` (width/height;
  failure → `decodable: false`, the HEIC case of `media.md` §2); video → hidden `<video>` with an
  object URL, `loadedmetadata` then `loadeddata` within `MEDIA_PROBE_TIMEOUT_MS`; revoke the URL.
- `makeThumbnail`: photo → the bitmap drawn on an `OffscreenCanvas` of `fitWithin` size →
  `convertToBlob({ type: 'image/webp', quality: quality / 100 })`; video → seek the probe video to
  the poster time, draw the frame on a canvas, same encode.
- `modifiedUtc`: `File.lastModified` when the blob is a `File`, else `null`.
- `discard`: no-op (garbage collection).

Thumbnail settings: `THUMBNAIL_MAX_EDGE = 512`, `THUMBNAIL_QUALITY = 80`,
`posterTimeMs = min(1000, durationMs / 2)` (`media.md` §3).

## 4. Client: import service (`src/services/media/import-service.ts`)

```ts
export type ImportedMedia = {
  readonly mediaId: string;
  readonly mediaKey: Uint8Array;
  readonly originalObjectId: string;
  readonly thumbnailObjectId: string;
  readonly originalCiphertextBytes: number;
  readonly thumbnailCiphertextBytes: number;
  readonly descriptor: MediaDescriptorV1;
  readonly time: ResolvedTime;
  readonly location: { readonly point: GeoPoint; readonly source: 'exif' } | null;
  readonly thumbnailBytes: Uint8Array;
};
export type ImportOutcome = { readonly ok: true; readonly media: ImportedMedia } | { readonly ok: false; readonly reason: ImportRefusal; readonly fileName: string | null };
export type ImportRefusal = 'unsupported' | 'undecodable' | 'tooLarge' | 'trayFull' | 'readFailed';
export function createImportService(deps: { sodium: Sodium; fileStore: FileStore; toolkit: MediaToolkit; clock: Clock; config: ConfigService; deviceSettings: DeviceSettings; randomId: () => string }): { importOne(input: MediaInput, slots: { readonly used: number; readonly limit: number }): Promise<ImportOutcome> };
```

`importOne` steps, in this order (cheap and local first, guidelines 3.2):

1. `slots.used >= slots.limit` → `trayFull`. New entries pass `limit = maxMediaPerEntry` (from
   `config.get() ?? CONFIG_FALLBACK`); adding to an existing entry (spec 13) passes
   `maxMediaPerEntry − existing media count`.
2. `byteSource`; read `SNIFF_HEADER_BYTES`; `sniffFormat` → `null` → `unsupported`.
3. `probe`; `decodable: false` → `undecodable` (on web this is the HEIC/HEVC refusal).
4. Ciphertext size of the original (`streamLayout(size, …).ciphertextSize`) `> maxUploadBytes` →
   `tooLarge`.
5. Metadata: photo jpeg/png/webp → `readImageMetadata`; heif → `pickerExif` only (null on
   camera/web); video mp4/mov → `readVideoMetadata`; webm → none. Picker EXIF fields fill only the
   fields the file reader left `null`.
6. `resolveCaptureTime` (camera: all embedded fields ignored, `nowUtc` from the clock).
7. Thumbnail (`makeThumbnail`).
8. Keys and ids: `mediaId`, `originalObjectId`, `thumbnailObjectId` = `randomId()`; `mediaKey =
   randomBytes(32)`; variant keys via `kdfDerive` (`crypto.md` §4).
9. Encrypt the original: `createStreamEncryptor(sodium, originalKey, size)`; an async generator
   reads `chunkSize` bytes at a time from the byte source and yields `encryptNext(chunk)` after
   the header; `fileStore.writeObject(originalObjectId, generator)`. Between chunks the generator
   yields to the event loop (`await new Promise(resolve => setTimeout(resolve, 0))`) so the UI stays
   responsive on web. Same for the thumbnail bytes (`byteSourceFromBytes`).
10. Android plain-thumbnail mode (`!deviceSettings.get().encryptThumbnails` on Android, always
    `false` on web; §4.2): also `fileStore.writePlainThumbnail(mediaId, thumbnailBytes)`.
11. `toolkit.discard(input)` (deletes the plaintext temp file), `wipe` variant keys.
12. Build `MediaDescriptorV1` (`kind`, `mimeType`, display `width`/`height`, `durationMs`,
    `originalPlaintextBytes = size`, thumbnail info, `capturedAtUtc`, `originalFileName` (null for
    camera)) and validate with the contract schema.

Any exception after step 8 deletes the objects written so far, discards the input, and returns
`readFailed` (logged in dev only with the error class name).

FileStore additions (07a interface, both platforms): `writePlainThumbnail(mediaId, bytes)`,
`plainThumbnailUri(mediaId): string | null`, `deletePlainThumbnail(mediaId)` — native writes
`thumbs-plain/<mediaId>.webp` under the account root (`data-model.md` §4.1); web implements them
as no-ops returning `null`. And `sweepOrphans(knownObjectIds: ReadonlySet<string>, olderThanMs:
number)`: deletes object files not in the set whose modification time is older than the cutoff
(called at unlock with `ORPHAN_SWEEP_AGE_MS = 3_600_000` and every object id from the RowStore;
removes files of trays abandoned by a killed app). The memory FileStore (spec 08) implements all
of these, and `fileStoreSelfTest` (07a) gains cases for them.

### 4.1 Temp files (`src/adapters/temp-files/`)

```ts
export type TempPurpose = 'import' | 'view' | 'play';
export interface TempFiles {
  readonly create: (purpose: TempPurpose, extension: string) => Promise<TempFile>;
  readonly wipe: () => Promise<void>;
  readonly list: () => Promise<readonly string[]>;
}
export type TempFile = { readonly uri: string; readonly write: (bytes: Uint8Array) => Promise<void>; readonly close: () => Promise<void>; readonly delete: () => Promise<void> };
```

- Native: files `<purpose>-<random>.<extension>` in `Paths.cache/kodakiko/<acct>/tmp/`
  (`keys/paths.ts` gains `tmpDirectory(acct)`); `write` appends through an open `FileHandle`;
  `uri` is the `file://` URI.
- Web: `create` throws `TempFilesUnsupportedError` (the web app never writes plaintext to storage;
  it uses in-memory `Blob`s); `wipe` and `list` are no-ops returning nothing.
- Native `wipe()` deletes **every child of `Paths.cache`** (not only `tmp/`): the camera
  (vision-camera), the Photo Picker copy, the image manipulator and the video-thumbnail tool all
  write their plaintext outputs into the app cache directory, and the app keeps nothing else there
  (objects and plain thumbnails live in `Paths.document`). It runs at app start, on lock and on
  sign-out (`data-model.md` §4.1: plaintext temp files never survive those). Spec 13 (`view`) and spec 15 (`play`) use the same port; the import path
  uses it for picker copies (§6.1). `list()` backs the diagnostics "List temp files" button.

### 4.2 Device settings (`src/services/settings/device-settings.ts`)

Created here because the import path is its first user; specs 14, 16 and 20 read and extend
nothing — the full schema is defined now:

| Field | Type | Default (Android / web) | Used by |
|---|---|---|---|
| `encryptThumbnails` | boolean | `false` / `true` (ignored on web, always encrypted) | 09, 14 |
| `originalCacheCapBytes` | int ≥ 0 (0 = no limit) | 2,147,483,648 / 1,073,741,824 | 14 |
| `largeUploadsOnWifiOnly` | boolean | `true` / `true` | 14 |
| `largeUploadThresholdBytes` | int ≥ 1,048,576 | 104,857,600 (100 MiB) / same | 14 (D-61) |
| `locationForCaptures` | boolean | `false` / `false` (D-55) | 09 |
| `autoLockMinutes` | int 1-60 | 5 / 15 | 16 |

`DeviceSettings = { get(): DeviceSettingsValue; update(partial): Promise<void>; subscribe(listener):
() => void }`; stored in kv `settings.device` as JSON with `schemaVersion: 1`, validated with
`deviceSettingsSchema` (missing or invalid → defaults, invalid fields reported in dev). Defaults per
platform come from `src/constants/device-settings-defaults.native.ts` / `.web.ts`.

## 5. Capture tray (`src/features/capture/`)

`capture-tray-store.ts`: an in-memory store (`items: ImportedMedia[]`, `add`, `remove(mediaId)`
(deletes both objects and the plain thumbnail), `reorder(from, to)`, `clear(deleteFiles: boolean)`,
`subscribe`). Lives in the app context; cleared with `deleteFiles: true` on lock and sign-out;
spec 10 calls `clear(false)` after saving the entry (the files now belong to the entry).
The tray shows thumbnails from `thumbnailBytes` as data URIs (memory only).

Device location (D-55): when the setting `settings.device.locationForCaptures` is `true` and the
origin is `camera`, the capture screen requests the current position once per capture session
(`expo-location` `getCurrentPositionAsync({ accuracy: Balanced })` with a 5 s timeout; web
`navigator.geolocation.getCurrentPosition` with the same timeout) and keeps it in the tray store as
`deviceLocation: { point, accuracyMeters } | null`. Spec 10 decides the entry's location: the
earliest media with `location`, else `deviceLocation` (source `device`).

## 6. Screens

### 6.1 `/capture` (modal over the tabs)

Android:
- Permission gate: camera permission missing → explanation text + "Allow Camera" button
  (`requestPermission`); denied without `canRequestPermission` → text + "Open Settings"
  (`Linking.openSettings()`). Microphone is requested on the first video (hold) only.
- Full-screen `<Camera>` with `usePhotoOutput({ containerFormat: 'jpeg', quality: 0.92 })` and
  `useVideoOutput({ enableAudio: true, fileType: 'mp4' })`; `isActive` false when the screen is
  not focused or the app is in the background.
- Shutter: press and release within `HOLD_TO_RECORD_MS = 300` → `capturePhotoToFile({ flashMode:
  'off', enableShutterSound: false }, {})`; hold longer → `createRecorder({})` + `startRecording`,
  a red ring and a `mm:ss` timer; release → `stopRecording()`; the finished file path goes to the
  import service as a camera input. No `maxDuration` (D-19); `maxFileSize` = the import service's
  ciphertext limit minus the STREAM overhead, so a recording never becomes `tooLarge`.
- Flip button (front/back). Close button (asks nothing if the tray is empty; else a sheet
  "Discard N items?" with "Discard" and "Keep").
- Gallery button → `expo-image-picker` `launchImageLibraryAsync({ mediaTypes: ['images', 'videos'],
  allowsMultipleSelection: true, selectionLimit: maxMediaPerEntry − trayCount, exif: true,
  quality: 1 })`; each asset → picker input (`uri`, `fileName`, `exif`). With `quality: 1` the
  picker copies the original file unchanged (metadata kept) into the app's cache directory and
  returns that `file://` URI (F-LIB-19); the import deletes that copy in `discard`.
- Tray strip at the bottom: thumbnails with a remove button each, an import spinner per pending
  item, and "Next" (enabled when ≥ 1 item) → `/entry/new` (spec 10).

Web:
- If `navigator.mediaDevices?.getUserMedia` exists: a camera view (`<video autoplay muted
  playsinline>`); tap → frame to canvas → `canvas.toBlob('image/jpeg', 0.92)`; hold →
  `MediaRecorder` with the first supported of `video/mp4;codecs=avc1.42E01E,mp4a.40.2`,
  `video/webm;codecs=vp9,opus`, `video/webm`; flip by re-requesting with `facingMode`. Without a
  camera (or permission denied) the view shows "Camera not available. Add photos and videos from
  your files instead."
- "Choose Files" (`<input type="file" multiple accept="image/jpeg,image/png,image/webp,image/heic,image/heif,video/mp4,video/quicktime,video/webm">`),
  drag and drop onto the screen (highlight while dragging), and paste (`paste` listener on
  `document` while the screen is mounted; `clipboardData.files`).
- Same tray and "Next".

Refusal messages (per item, collected into one `Banner` after a batch):

| Reason | Text |
|---|---|
| `unsupported` | `(name) => \`${name} isn't a supported photo or video.\`` |
| `undecodable` (web) | `(name) => \`This browser can't read ${name}. Add it from the Android app instead.\`` |
| `undecodable` (Android) | `(name) => \`${name} couldn't be opened.\`` |
| `tooLarge` | `(name) => \`${name} is larger than this server allows.\`` |
| `trayFull` | `(max) => \`An entry can hold up to ${max} photos and videos.\`` |
| `readFailed` | `(name) => \`${name} couldn't be added. Try again.\`` |

Camera captures have no file name; messages use "This photo" / "This video".

## 7. Tests

Core (Node, `packages/core/test/media/`), with **synthetic** files built in the tests (helpers in
`packages/core/test/media/builders.ts` build TIFF/JPEG/PNG/WebP/MP4 byte arrays; the unit tests use
no binary fixtures). This spec also adds `tests/fixtures/media/add-exif.ts` and `check-fixtures.ts`
(spec 07c §6) and writes UI flow U-08 (07c §8) first.

1. `sniffFormat` for every row of the table, plus a HEIF file whose major brand is `mif1`, an MP4
   with major brand `isom`, and 10 random byte arrays → `null`.
2. EXIF: little- and big-endian TIFF with DateTimeOriginal, OffsetTimeOriginal, GPS (N/E and S/W),
   orientation 6; JPEG with the APP1 after an APP0; PNG `eXIf`; WebP `EXIF`; truncated TIFF →
   `malformed: true` and no throw; IFD loop (pointer to itself) → terminates; GPS `0,0` → `null`.
3. Video: `mvhd` v0 and v1 times; `moov` after a 50 MiB `mdat` (byte source fake that serves
   headers from a virtual size, never allocating 50 MiB); `©xyz` with and without altitude; Apple
   keys/ilst location; oversized `moov` → `malformed`.
4. `resolveCaptureTime` precedence table (8 cases) and `earliestTime`.
5. `fitWithin` / `displaySize` table.

Client (Node, fakes for `MediaToolkit`, memory FileStore, wrappers Sodium):

6. `importOne` happy path: objects written, both decrypt (core decryptor) to the original bytes and
   the thumbnail bytes; descriptor validates; `discard` called once.
7. Refusals: unknown header → `unsupported` and nothing written; probe `decodable: false` →
   `undecodable`; size above the limit → `tooLarge`; tray full → `trayFull` before any read.
8. Failure in `writeObject` of the thumbnail → the original object is deleted, `readFailed`.
9. `pickerExifToMetadata` with number and string GPS forms.
10. `sweepOrphans` deletes only unknown, old files.

Manual (Android dev build and Chrome), each a checklist line in §9.

## 8. Steps

1. Confirm the installed 5.2.4 type definitions match `F-LIB-18`; report differences before coding
(stop and ask on a mismatch). 2. Core media modules + tests. 3. App config,
packages, prebuild. 4. Toolkit adapters (record `F-LIB-19` with the exact SDK 58 manipulator /
thumbnail / player calls used). 5. Import service, FileStore additions, tray store + tests.
6. Capture screen (Android, web). 7. Manual runs.

## 9. Verification checklist

- [ ] `pnpm verify` green (paste summaries).
- [ ] Android: tap → photo appears in the tray within 1 s on a mid-range phone (report the time);
      hold 10 s → video in the tray with a poster thumbnail; flip camera works.
- [ ] Android: pick 3 photos (one HEIC if available) and 1 video from the Photo Picker → 4 tray
      items; times come from EXIF/container (inspect the descriptor in a dev log line that prints
      only `capturedAtUtc` and `time.source`).
- [ ] Android: after import, the app cache contains no plaintext temp file (list `tmp/` via a dev
      diagnostics button "List temp files").
- [ ] Android: kill the app with 2 items in the tray → reopen and unlock → `sweepOrphans` removes
      their objects after the age cutoff (temporarily set to 0 in a dev build; report).
- [ ] Web (Chrome): camera photo and video; "Choose Files" with JPEG + MP4; drag and drop; paste a
      screenshot (time source `now`); a HEIC file → the "This browser can't read …" message.
- [ ] Encrypted object on disk/OPFS starts with `KDKS` and decrypts with the media key in a dev test.
- [ ] With location off (default) no location permission prompt ever appears; with it on, the
      tray keeps `deviceLocation` for camera captures only.
- [ ] Lint: no numbers outside constants files in the readers (`media/constants.ts` holds every
      signature byte, tag id and limit).
