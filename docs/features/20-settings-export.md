# 20 — Settings, password change, account deletion, journal export

Status: draft 1 (2026-11-11).

Goal: one Settings area where the user manages the account (password, security code, recovery key,
deletion), security (auto-lock, keep unlocked, thumbnail encryption, location), storage (usage,
cache limit, clearing, Wi-Fi rule), sync status, public links and admin (when allowed), and can
export the whole journal as decrypted zip parts (D-28, D-52, D-66).

Read first: `docs/design/client.md` §§1, 6, 8; `docs/design/crypto.md` §§5.4, 5.6; specs 04 (password
change, deletion), 07b (auth service), 09 §4.2 (device settings), 14 (usage, cache, mode switch),
16 (lock, keep unlocked), 17 (security code), 18 (links list), 19 (admin); facts F-LIB-25, F-LIB-26;
decisions D-26, D-28, D-52, D-55, D-61, D-63, D-66, D-67. Depends on 14, 16, 17, 18, 19.

## 1. Settings structure (`app/settings/…`)

`/settings` (`app/settings/index.tsx`, opened from a gear button in the gallery header, spec 11 §4.1;
not a tab) lists, in this order, one row each:

| Row | Route | Shown when |
|---|---|---|
| Account | `/settings/account` | always |
| Security | `/settings/security` | always |
| Storage | `/settings/storage` | always |
| Sync | `/settings/sync` | always |
| Public Links | `/settings/links` (spec 18) | `publicLinksEnabled` |
| Export Journal | `/settings/export` | always |
| Admin | `/settings/admin` (spec 19) | user is admin |
| About | `/settings/about` | always |
| Lock Now | action (`lockNow`, spec 16) | always |
| Sign Out | action (07b; confirmation when the outbox is not empty, spec 16 §5 text) | always |

## 2. Account (`/settings/account`)

- Username, server (`serverBaseUrl` host), storage "Using (used) of (quota)" from `GET /users/me`
  (refreshed on screen focus; offline → last known values with "as of (time)").
- "Server": the host; on Android a "Change Server…" button (D-73) → sheet "Change server? You'll be
  signed out on this device." plus, when the outbox is not empty, "(n) unsynced changes on this
  device will be lost." → "Change Server" → `authService.changeServer()` = the sign-out flow (07b
  step 10) followed by clearing `kodakiko.server` and `kodakiko.serverConfig` → `/welcome`. Web shows
  the host only (the web app always talks to the server it is served from).
- "Your security code": `sharingService.myFingerprint()` (spec 17) in monospace, with "Others can
  compare this code before sharing with you."
- **Change Password…** → `/settings/password`: current password, new password, confirm; core
  password policy hints; "Change Password":
  1. `createPasswordChangeMaterial` (spec 04 §2.8; shows the `/securing`-style static view while
     Argon2id runs; `WrongPasswordError` → "Your current password is incorrect.").
  2. `POST /auth/password`; on success save the new session token, kv `account.kdfParams` and
     `account.masterKeyByKek`. The master key did not change, so the Android KeyVault item and the
     web keep-unlocked record stay as they are (re-storing on Android would show a needless
     biometric prompt).
  3. `Banner` "Password changed. Other devices need the new password the next time they unlock."
- **Show Recovery Key…**: asks for the password (derive + unwrap check, no server call), then shows
  the 24 words (`encodeRecoveryKey` of the unwrapped `recoveryKeyByMaster`) with the 07b text and
  "Done". The words are kept in component state only and cleared on leave and on lock.
- **Delete Account…** → `/settings/delete-account`: text "Your account and journal will be
  erased (graceDays) days from now. You can cancel until then by signing in again. After that,
  nothing can be recovered." (`graceDays` comes from `PublicConfigDto.accountDeletionGraceDays`,
  api §11); a field "Type your username to confirm"; password field; "Delete My Account" (`danger`)
  enabled only when the username matches → verify the password locally (derive + unwrap) →
  `authService.requestAccountDeletion()` → `POST /users/me/delete` (`202 { deletionDueAt }`) → the
  server revoked **every** session including this one (D-29), so the client clears the session
  token (keeps the RowStore, so nothing is lost if the user cancels), goes to `signedOut`, and
  `/sign-in` shows `Banner` `account.deletionScheduled` "(date) =>
  \`Your account will be deleted on ${date}. Sign in before then to keep it.\`" with the username
  prefilled. Signing in reaches `/cancel-deletion` (07b) (D-71).

Auth service additions (extend 07b's `AuthService` type in this spec):

```ts
readonly changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
readonly revealRecoveryWords: (password: string) => Promise<readonly string[]>;
readonly verifyPassword: (password: string) => Promise<boolean>;
readonly requestAccountDeletion: () => Promise<{ readonly deletionDueAt: string }>;
readonly changeServer: () => Promise<void>;
```

## 3. Security (`/settings/security`)

| Control | Behavior |
|---|---|
| Auto-lock | picker 1, 2, 5, 10, 15, 30, 60 minutes → `deviceSettings.update({ autoLockMinutes })`; label "Lock after (n) minutes in the background" (Android) / "of inactivity" (web) |
| Keep me unlocked on this browser (web) | switch → `authService.setKeepUnlocked` (spec 16 §3) with its hint text |
| Unlock with fingerprint or device PIN (Android) | read-only status from `KeyVault.capabilities().biometric`: "On" / "Set up a fingerprint or screen lock in Android settings to use it." |
| Encrypt thumbnails on this device (Android) | switch → confirmation sheet "Thumbnails will be (re)built in the background." → `thumbnailCache.switchMode` (spec 14 §5) with the progress line "Updating thumbnails… (done)/(total)"; the switch is disabled while a switch runs. Hint: "Off: thumbnails on this phone are stored unencrypted inside the app, which is faster. On: they stay encrypted on this phone too." |
| Add location to new photos | switch → `deviceSettings.update({ locationForCaptures })`; turning it on requests the location permission right away (denied → switch back off with "Location permission is off for Kodakiko.") |

## 4. Storage (`/settings/storage`)

- Usage from `originalsService.usage()` (spec 14): "Full-size photos and videos (originalsBytes)",
  "Thumbnails (thumbnailsBytes)", "Kept offline (pinnedBytes)", "Waiting to upload
  (pendingUploadBytes)"; values via `formatBytes` (spec 19 §2.3).
- "Keep downloaded originals up to": picker values `ORIGINAL_CACHE_CAP_CHOICES = [536_870_912,
  1_073_741_824, 2_147_483_648, 5_368_709_120, 10_737_418_240, 0]` (binary: 512 MiB … 10 GiB, the
  defaults of spec 09 are among them), labeled with `formatBytes` (spec 19 §2.3, which uses binary
  units with "GB"/"MB" labels for people), `0` labeled "No limit" (D-67)
  → `deviceSettings.update({ originalCacheCapBytes })` then `enforceCap()`.
- "Clear Downloaded Originals" → confirmation "Remove downloaded full-size photos and videos from
  this device? They stay in your journal and download again when you open them." → `clearOriginals()`.
- "Upload large files on Wi-Fi only" switch and "Large means larger than" picker with
  `LARGE_UPLOAD_THRESHOLD_CHOICES = [52_428_800, 104_857_600, 262_144_000, 524_288_000,
  1_073_741_824]` (50 MB, 100 MB, 250 MB, 500 MB, 1 GB via `formatBytes`; default 100 MB) →
  `deviceSettings.update({ largeUploadThresholdBytes })` (D-61; Android only; web hides both).
- Web: browser storage persistence status from kv `device.storagePersisted`: "Protected from
  automatic cleanup" or "The browser may clear offline data when space runs low." with "Ask
  Again" (`navigator.storage.persist()`).

## 5. Sync (`/settings/sync`)

`syncService.status()`: "Last synced (relative time)" / "Not synced yet"; "Waiting: (queued)";
"Failed: (dead)"; "Waiting for Wi-Fi: (waitingForWifi)"; buttons "Sync Now" (`syncNow`, shows a
spinner) and "Retry Failed" (`retryDead`, only when dead > 0). Below: the dead items (from
`outbox.all()` filtered `dead`) as rows "(kind label) — (entry date or id) — (error text)", where
kind labels and error texts come from frozen maps in `text/en.ts`: kinds `createEntry` → "New entry",
`updateEntryMeta` → "Edit", `addMedia` → "Added photo or video", `removeMedia` → "Removed photo or
video", `trashEntry` → "Move to trash", `restoreEntry` → "Restore", `deleteEntry` → "Delete";
errors (`QUOTA_EXCEEDED` → "Storage full", `FEATURE_DISABLED` → "Turned off on the server", `VALIDATION_FAILED` → "Rejected by the
server", network → "Network problem", other → "Server error").

## 6. About (`/settings/about`)

App version (`expo-constants` `expoConfig.version`), server version (`ConfigService`), "Open-source
licenses" → a list read from `assets/licenses.json` (generated at build time by spec 21's
`pnpm licenses list --json` step; this spec renders `{ name, version, license }[]`, sorted by name;
the repository commits `apps/client/assets/licenses.json` as `[]` (so the bundle always resolves it);
an empty list shows "License list is generated at release build time."; spec 21 overwrites it in
release builds).

## 7. Journal export (`/settings/export`, D-28, D-52, D-66)

### 7.1 Core: export layout (`packages/core/src/export/`, pure)

```ts
export type ExportEntryPlan = { readonly folder: string; readonly noteFile: string; readonly noteText: string; readonly media: readonly { readonly mediaId: string; readonly fileName: string; readonly plaintextBytes: number }[]; readonly mtime: Date };
export function planEntryExport(entryId: string, metadata: EntryMetadataV1): ExportEntryPlan;
export function frontMatter(entryId: string, metadata: EntryMetadataV1): string;
export type PlannedFile = { readonly entryId: string; readonly path: string; readonly bytes: number };
export function planParts(files: readonly PlannedFile[], partMaxBytes: number, partMaxFiles: number): readonly (readonly PlannedFile[])[];
export function partFileName(exportDate: string, partNumber: number): string;
```

- Folder: `YYYY/MM/YYYY-MM-DD_HHMM_<first 8 chars of entryId>/` from the entry's local time (D-45).
- Media file names: three-digit position (`001`, `MEDIA_PER_ENTRY_HARD_MAX` is 100) + extension from the MIME type (`001.jpg`, `002.mp4`, map in
  `export/constants.ts`); the original file name, when known, is recorded in the front matter, never
  used as a path (no path injection).
- `entry.md`: YAML front matter then the note:
  ```
  ---
  id: <entryId>
  date: 2026-11-11T14:05:00+01:00
  tags: [beach, family]
  location: { latitude: 37.38610, longitude: -122.08390, source: exif }
  media: [{ file: 001.jpg, original_name: IMG_1234.JPG, kind: photo }]
  ---

  <note text>
  ```
  YAML scalars are quoted with double quotes when they contain any of `:#[]{},"'` or start/end
  with whitespace; `"` and `\` escaped (`yamlQuote`, tested).
- `planParts(entries, partMaxBytes, partMaxFiles)`: greedy in chronological order over **files**:
  each media file and each `entry.md` is placed in the current part if the part's bytes + file bytes
  + `EXPORT_FILE_OVERHEAD_BYTES (1024)` ≤ `partMaxBytes` and its file count < `partMaxFiles`
  (`EXPORT_PART_MAX_FILES = 65_000`, below ZIP's 65,535 limit, since fflate writes no Zip64,
  F-LIB-26), else a new part starts. An entry's files may therefore span parts (its folder appears
  in each). A single file is at most 3.5 GiB (upload cap, D-66), below the 3.75 GiB part limit, so a
  part never reaches 4 GiB.
- `partFileName('2026-11-11', 3)` → `kodakiko-export-2026-11-11-part03.zip`.
- Root file `README.txt` in part 1 (text in core `export/text.ts`): what the folders are, that the
  files are **not encrypted**, and the export date.

### 7.2 Client: export service (`src/services/export/export-service.ts`)

```ts
export type ExportProgress = { readonly phase: 'planning' | 'downloading' | 'writing' | 'done'; readonly entriesDone: number; readonly entriesTotal: number; readonly bytesWritten: number; readonly partNumber: number; readonly partsTotal: number };
export type ExportSummary = { readonly parts: number; readonly entries: number; readonly mediaFiles: number; readonly bytes: number; readonly skippedUnreadable: number; readonly skippedMissing: number; readonly skippedTooLarge: number };
export interface ArchiveWriter {
  readonly addFile: (name: string, options: { readonly compress: boolean; readonly mtime: Date }) => ArchiveFile;
  readonly finish: () => Promise<void>;
}
export interface ArchiveFile { readonly push: (bytes: Uint8Array, final: boolean) => Promise<void> }
export type ExportDestination = { readonly partMaxBytes: number; readonly createPart: (name: string) => Promise<PartSink> };
export type PartSink = { readonly write: (bytes: Uint8Array) => Promise<void>; readonly close: () => Promise<void>; readonly abort: () => Promise<void> };
export class ExportCancelledError extends Error {}
export function createExportService(deps: { readonly index: IndexService; readonly entries: EntriesService; readonly entryKeys: EntryKeys; readonly rowStore: RowStore; readonly fileStore: FileStore; readonly downloader: Downloader; readonly originals: OriginalsService; readonly sodium: Sodium; readonly autoLock: { pause(): void; resume(): void }; readonly clock: Clock; readonly createArchiveWriter: (sink: PartSink) => ArchiveWriter }): { run(destination: ExportDestination, onProgress: (p: ExportProgress) => void): Promise<ExportSummary>; cancel(): void };
```

`ArchiveWriter` is the D-52 port; `src/adapters/archive/fflate-archive-writer.ts` implements it
with `fflate` (F-LIB-26, verified API): `const zip = new Zip((error, chunk, final) => …)`; for each
file create `new ZipDeflate(name, { level: 6 })` (`compress: true`) or `new ZipPassThrough(name)`
(`compress: false`), set `file.mtime = clampZipDate(mtime)`, call `zip.add(file)` **before** the
first `file.push`; `push(bytes, final)`; `finish()` → `zip.end()` and await the final chunk. The
`Zip` callback is synchronous: it appends chunks to a queue that one async loop drains with `await
sink.write(chunk)`; `ArchiveFile.push` awaits until the queue is empty again (back-pressure, never
more than one chunk ahead).

`clampZipDate` (core `export/zip-dates.ts`): ZIP dates must be within 1980-01-01 and 2099-12-31
(fflate throws otherwise, verified) → clamp to `EXPORT_MTIME_MIN` / `EXPORT_MTIME_MAX`.

`run`, in order:
1. `autoLock.pause()` (resumed in `finally`).
2. Planning (after the destination was chosen, because the part limit depends on it): own
   (`isOwn`), non-trashed, readable entries from the index, chronological; open each metadata
   (`entries.readMetadata`); `planEntryExport`; flatten into `PlannedFile`s (`entry.md` and media in entry order); `planParts(files,
   destination.partMaxBytes, EXPORT_PART_MAX_FILES)` (§7.1). Media larger than `destination.partMaxBytes` (possible only on the
   web fallback, 1 GiB) are skipped and counted `skippedTooLarge`.
3. For each part: `sink = await destination.createPart(partFileName(...))`; `writer =
   createArchiveWriter(sink)`.
4. Per entry: `entry.md` (compressed). Per media original (stored, `mtime` = entry time):
   - the original object row; `localState` not `present` → `downloader.fetchObject(id, 'opened')`
     (phase `downloading`); `unavailable` or a failed download → skip, count `skippedMissing`;
   - `key = entryKeys.variantKey(entryId, mediaId, 'original')`; `decryptor =
     createStreamDecryptor(sodium, key, header, plaintextSize)` with `header =
     fileStore.readRange(id, 0, 24)`; read each ciphertext chunk with `fileStore.readRange`, decrypt,
     `await file.push(plaintext, isLast)`; `decryptor.finish()`; `wipe(key)`.
   - **No plaintext is ever written to the app's storage** (no `OriginalSource`, no temp file).
   - After the entry: `originals.enforceCap()`.
5. Part 1 also gets `README.txt` (stored) and, at the end of the export, part 1 cannot be appended to
   anymore, so skipped items are listed in `MISSING.txt` inside the **last** part (`entry folder /
   file name / reason`), when any were skipped.
6. `writer.finish()`; `sink.close()`.
7. `cancel()` (user, or lock step 0 `cancelExport`, spec 16) → the current part's `sink.abort()`;
   `run` rejects with `ExportCancelledError`; finished parts stay.
8. Return the summary.

### 7.3 Destinations (`src/adapters/export-destination/`)

- Native (`partMaxBytes = EXPORT_PART_MAX_BYTES`): `Directory.pickDirectoryAsync()` (F-LIB-25;
  cancelled → no export); `createPart(name)` → `directory.createFile(name, 'application/zip')` →
  `file.open(FileMode.WriteOnly)` (a Storage Access Framework file opened without a mode is
  read-only, and `ReadWrite` is rejected for `content://` URIs, verified) → `FileHandle.writeBytes`;
  `close` closes the handle; `abort` closes the handle, then deletes the file.
- Web with `showDirectoryPicker` (Chrome/Edge; `partMaxBytes = EXPORT_PART_MAX_BYTES`): `await
  showDirectoryPicker({ mode: 'readwrite' })`; `createPart` → `getFileHandle(name, { create: true
  })` → `createWritable()`; `abort` → `writable.abort()` then `removeEntry(name)`. The File System
  Access API types are not in TypeScript 6's DOM library: declare the used subset in
  `src/adapters/export-destination/file-system-access.d.ts` (`showDirectoryPicker`,
  `FileSystemDirectoryHandle.getFileHandle`/`removeEntry`, `FileSystemFileHandle.createWritable`,
  `FileSystemWritableFileStream.write/close/abort`).
- Web fallback (`partMaxBytes = EXPORT_WEB_MEMORY_PART_MAX_BYTES`, 1 GiB): chunks collected into a
  `Blob` per part; on `close` a hidden `<a download=name>` with an object URL is clicked, then the
  URL revoked. The screen says "Your browser will download (partsTotal) files. Videos larger than
  1 GB are skipped here; export them from Chrome or the Android app."

### 7.4 Screen

- Explanation: "Export saves every entry of your journal — photos, videos and notes — as zip files.
  The files are **not encrypted**. Store them somewhere safe." Estimated size (from the plan before
  starting) and number of parts.
- "Choose Folder and Export" → destination picker → progress (phase, entries n/N, part p/P, bytes),
  "Cancel". Keep the screen awake is not available (no dependency); the text says "Keep Kodakiko open
  until the export finishes." Done → summary and "Export finished. (parts) files, (size)."
- Auto-lock during an export would cancel it (spec 16): while an export runs, the auto-lock timer
  (spec 16 §2) is paused with `autoLock.pause()` and resumed after; manual lock still cancels.

### 7.5 Package

Add `fflate` (allowlist) to `apps/client`.

## 8. Tests

- Core: `planEntryExport` (folder names across offsets and midnight, extension map, position
  numbering), `frontMatter`/`yamlQuote` (tags with colons, quotes, emoji; null location omitted),
  `planParts` (greedy split by bytes, split by file count at 65,000, an entry spanning two parts,
  empty journal → no parts), `clampZipDate` (1970 → 1980-01-01, 2150 → 2099-12-31).
- Client (Node, memory adapters, a memory `ExportDestination`): export of 3 entries (one with a video
  and a photo) → unzip the produced parts with `fflate.unzipSync` in the test → files and contents
  equal the originals and the expected `entry.md`; cancel midway → the unfinished part is aborted and
  earlier parts remain; a part limit small enough to force 2 parts → 2 valid zips.
- Password change flow (fake HTTP): kv updated, session replaced; wrong current password → no request.
- Account deletion screen logic: button disabled until the username matches.

## 9. Verification checklist

- [ ] `pnpm verify` and e2e green.
- [ ] Android: export a journal of ~2 GB into a folder on the phone → open the zip in a file manager;
      photos/videos play; `entry.md` has the right date, tags, location and note.
- [ ] Chrome: export into a chosen folder; Firefox: parts download one by one (≤ 1 GiB each).
- [ ] Change the password on Android → web unlock with the old password fails and with the new one
      succeeds (key-bundle refresh, spec 07b).
- [ ] Show Recovery Key → the same 24 words as at sign-up.
- [ ] Delete account → `/cancel-deletion`; "Keep My Account" restores; a second deletion request,
      then the server's erase job (clock moved in a dev server) removes all data.
- [ ] Settings rows hidden/shown per flags and role.
- [ ] Storage cap "No limit" keeps all opened originals; a 512 MB cap evicts the oldest.
