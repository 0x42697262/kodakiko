# 25 — Edit page, captions, voice notes, share to apps (v0.3.0)

Status: draft 1 (2026-11-14, Opus, autopilot). Source: `docs/planning/v0.3.0-entry-composer.md`
(PicDiary research) and the programmer's answers D-86 … D-100. Base: v0.2.2
(`feat/v0.2.2-native-feel`). Branch `feat/v0.3.0-composer`, one commit per phase.

Capture stays its own full-screen page (v0.1.3 flow). Only the edit page changes.

## 0. Decisions

| Topic | Decision |
|---|---|
| One edit page (D-86) | `ComposeScreen` and `EntryEditScreen` merge into `features/entries/EditPage.tsx`, used for a new entry (`/entry/new`, from capture) and an existing one (`/entry/[id]/edit`). The Viewer stays the read page |
| Autosave (D-89) | A new entry is created as soon as the edit page opens with the tray's media (`createFromTray`); every later change (note, tags, time, place, order, favorite, captions) is written through `entries.edit` after a short debounce (600 ms; note typing only). No Save/Discard. The app bar's check icon (`compose.save`, kept test id) leaves the page and shows the "Saved." Snackbar |
| Layout (planning §4) | App bar (back, time as title, ⋮ with When / Where / Tags / Move to trash); media pager with page dots (hidden for 0–1 items); icon action bar: photo, video, voice note, add from device, paste (when there is an image), favorite, share; voice notes list; note; details rows as icon actions |
| Note (D-94) | New entry: an "Add a note" row; one tap opens the editor. Existing note: rendered markdown with a pencil icon; touching the text never starts editing. In edit mode a toolbar inserts markdown: bold `**`, italic `*`, strikethrough `~~`, heading `# `, bullet `- `, checklist `- [ ] `, quote `> `, link `[…](url)`, current time. `features/markdown/toolbar.ts` (pure, unit-tested) |
| Captions (D-88) | `MediaDescriptorV1.caption?: string` (1–64 chars, optional, absent = none; existing blobs stay valid, like `favorite`). One-line overlay at the bottom of the page in the edit page and viewer. Included in gallery search and in export (`entry.md` media list) |
| Per-media ⋮ (D-90) | Caption, Set as cover (move to position 1), Save to device, Share, Remove (confirm), Crop (photos). **Trim is deferred to v0.3.1**: no maintained Expo/React Native trim package without ffmpeg-kit (~25 MB) exists, and a Media3 Transformer module needs a device build to verify (disk floor). |
| Crop (D-90, D-97) | No new dependency: Android `expo-image-manipulator` (installed) crops the decrypted local copy; web crops through a canvas. Free-form rectangle with corner handles; result replaces the original (new media id, old objects garbage-collected through the normal remove path) |
| Inline video (D-91, D-99) | Video pages autoplay muted, loop, tap the speaker to unmute, pause off-screen. Settings › Storage: **Autoplay videos** (on) and **Autoplay on mobile data** (off). A video not yet cached downloads only on Wi-Fi unless the second switch is on. Tapping the video still opens the Kodakiko player |
| Voice notes (D-95) | `MediaDescriptorV1.kind` adds `'audio'`. Audio items keep the required `thumbnail`: it is generated from a bundled waveform picture through the normal thumbnail pipeline, so gallery, links and sync need no special case; `width`/`height` are the thumbnail's. An entry may be audio only. Recording: `expo-audio` (Android, AAC `.m4a`) and `MediaRecorder` on web (Opus `.webm`); both play on both platforms. Gallery tile for audio-only entries: mic icon + duration; an audio badge |
| Paste (D-96) | `expo-clipboard` (`hasImageAsync` to show the button, `getImageAsync` on tap only, written to a temp file, then the normal import pipeline). Web keeps Ctrl+V (spec 09) |
| Share (D-93, D-98) | One Share sheet: "To apps" (decrypted files through the OS share sheet) and "In Kodakiko" (spec 17/18). Share picker: thumbnail grid, all checked, count and size on the button, warning above 500 MB, text option None / Captions / Captions and note. Android: `react-native-share` 12.3.1 (MIT; `ACTION_SEND_MULTIPLE`; `expo-sharing` sends one file only). Web: `navigator.share({ files })` where `canShare` allows, otherwise the option is hidden. Temp files are deleted on the next app start |
| Share via link while uploading (D-100) | The link is created at once; until the entry's media are on the server, opening the link shows "Not ready yet". Spec 17 account sharing stays in the sheet (planning Q10: X) |
| Delete originals (D-92) | **Deferred to v0.3.1.** Matching Photo Picker results to MediaStore ids needs `expo-media-library`, the `READ_MEDIA_*` permissions and device checks that cannot be done without an APK build |
| Gallery FAB | Icon only (no "New" text) |

## 1. Phases

1. Spec, contract (`caption`, `audio`), dependencies allowlisted.
2. Edit page with autosave, action bar, pager dots, note editor and toolbar, icon FAB.
3. Per-media ⋮: caption, set as cover, save to device, remove, crop.
4. Inline video autoplay and its two settings.
5. Share sheet, share picker, share to apps, paste.
6. Voice notes.
7. Link while uploading; screenshots; version 0.3.0.

## 2. Tests

- Contract: caption length 1–64, optional; audio kind; old blobs still parse.
- Core/client unit: markdown toolbar edits; autosave debounce; caption in search and export;
  set-as-cover order; crop rectangle math; autoplay rule (`shouldAutoplay(cached, wifi,
  settings)`); share-picker totals and the 500 MB warning; audio tile model.
- Web UI flows: existing flows adapted only where D-86/D-89 remove the Compose/Edit split (noted
  per flow); new **U-34** autosave (type a note, leave without Save, reopen → note kept), **U-35**
  caption shows on the page and is found by search, **U-36** set as cover changes the gallery
  tile, **U-37** voice note recorded on web (fake microphone) plays back.
- Android (UNVERIFIED until an APK): paste, share to apps, crop, autoplay, recording.

## 3. Out of scope

Trim and delete originals (v0.3.1); transcription; PicDiary's player; auto note editing.

## 4. Implementation notes
- **Phase 1:** contract `CAPTION_MAX_CHARS` 64, `MediaDescriptorV1.caption` optional, `kind`
  adds `'audio'` (contract tests red first). Core merge keeps a caption changed on one side
  (`withMergedCaption`; merge tests red first). Dependencies allowlisted and installed:
  `expo-audio`, `expo-clipboard`, `react-native-share`.
- **Phase 2:** `EditPage.tsx` replaces `ComposeScreen` and `EntryEditScreen` (deleted).
  `/entry/new` (`NewEntryScreen`) creates the entry from the tray at once and replaces itself with
  `/entry/<id>/edit?new=1`. `autosaver.ts` (unit tests red first): immediate writes for
  time/place/tags, 600 ms debounce for the note, `flush()` before leaving, opening capture,
  favorite and trash; each write diffs against freshly read metadata (`diffDraft`).
  Media pager (thumbnails for now) with `PageDots`; action bar: photo and video (open capture with
  `entryId` and `mode`, which sets the camera's start mode), add from device (opens capture, whose
  import button is one tap away), favorite. `note-section.tsx`: "Add a note" row, rendered note +
  pencil, editor with the toolbar (`markdown/toolbar.ts`, unit tests red first). Gallery FAB is
  icon only. `TextField` gains `selection`, `onSelectionChange`, `autoFocus`.
  Tests adapted to D-86/D-89 (programmer decisions supersede spec 10's Compose/Edit steps):
  entry-compose (pages instead of strip tiles; "a tray change drops the edited time" became "the
  edited time is kept after leaving"; "Back returns to capture with the tray intact" became "Back
  keeps the new entry and returns to the gallery"), U-06, U-07 and the screenshot script tap
  "Add a note" first. New **U-34** (written with the code): note autosaved on Back, toolbar bold.

