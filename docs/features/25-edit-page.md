# 25 — Edit page, captions, voice notes, share to apps (v0.3.0)

Status: implemented (2026-11-14, Opus, autopilot); Android items UNVERIFIED until the v0.3.0 APK. Source: `docs/planning/v0.3.0-entry-composer.md`
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
  tile, **U-37** crop replaces the photo on the server, **U-38** inline autoplay and its setting, **U-39** share picker + paste, **U-40** voice note recorded on web (fake microphone) plays back.
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
- **Phase 3:** core `EntryEdit.captions` (trimmed; empty clears; unit tests red first); client
  `EditDraft.captions` + `diffDraft` (red first); search text = note + captions
  (`services/index/search-text.ts`, red first); export writes `caption:` per media and the import
  parser and journal import restore captions and accept `kind: audio` (red first). Edit page: a ⋮ on
  every page opens `media-menus.tsx` (Caption, Set as Cover, Save to Device, Crop for photos,
  Remove with a confirm; the last item cannot be removed). Caption overlay on edit pages; the
  viewer shows the caption as a line under the pager (overlays fight the zoom gestures).
  Crop: `crop-math.ts` (red first), `crop-sheet.tsx` (corner handles and drag with PanResponder),
  `adapters/image-crop` (Android `expo-image-manipulator`, web canvas), `replace-media.ts` (red
  first: import, add, remove old, restore position and caption). Save to Device:
  `adapters/share-files` (web download link; Android share sheet, where "Save to Files" lives).
  Web: the CSP forbids `fetch(blob:)`, so `plaintext-sink.web` registers its blobs
  (`blob-registry.web.ts`) for crop and share. Found while testing: after a reorder the web viewer
  kept showing the old first photo (browser scroll anchoring); the pager now remounts when the
  media order changes. New flows **U-35** caption + search, **U-36** set as cover + remove
  confirm, **U-37** crop (written with the code).
- **Phase 4:** `features/video/autoplay.ts` `shouldAutoplay` (red first); device settings
  `autoplayVideos` (on) and `autoplayOnMobileData` (off) (red first); Data and storage shows
  **Autoplay videos** on every platform and **Autoplay on mobile data** on Android (web has no
  mobile-data signal). `inline-video.tsx` prepares the video only when autoplay is wanted (cached
  = original object `present`; Wi-Fi from the new `services.network`) and plays muted + looped on
  the current page only, with a mute toggle. Players: `inline-player.native.tsx` (`expo-video`)
  and `inline-player.web.tsx` (a `<video>` element, because expo-video's web player leaves
  `play()` rejections unhandled when a page is swiped away). Used in the edit page (tap opens the
  Kodakiko player) and the viewer (its centered Play overlay passes touches through,
  `pointerEvents="box-none"`). **U-38** (written with the code). U-13 (no full download) still
  passes.
- **Phase 5:** `features/sharing/share-picker-model.ts` (count, size, 500 MB warning, text option;
  red first). `ShareSheet.tsx`: tabs "In Kodakiko" (spec 17/18 sheet, first when available so the
  existing flows stay unchanged) and "To apps" (picker grid, all checked, text chips, size warning,
  "files leave encryption" note, unsupported-browser warning); `shareOriginals` opens each
  original and hands the files to `adapters/share-files` (Android `react-native-share`, web
  `navigator.share({ files })`), releasing the decrypted copies after 60 s. Viewer action row Share
  is now shown for every readable entry; bulk Share is always offered. Edit page: Share in the
  action bar and per item. Paste: `adapters/clipboard-image` (Android `expo-clipboard` button when
  the clipboard has an image, written to an import temp file; web listens for paste events while
  the edit page is focused). Found while testing: the viewer's inline video kept streaming under
  the full player and broke U-13; inline videos now stop when their screen loses focus and start
  only after a page has been shown for 0.8 s (also saves data on quick swipes). **U-39** (written
  with the code). `react-native-share`'s config plugin is not added (iOS-only entries).
- **Phase 6:** `importService.importVoiceNote` (unit tests red first): audio items skip probing and
  thumbnail rendering; the thumbnail is a bundled 256 px waveform WebP
  (`constants/voice-note-constants.ts`), so gallery, sync, links and export treat audio like any
  item. The import code's store step is shared (`persist`). Recorder: `adapters/voice-recorder`
  (Android `expo-audio` HIGH_QUALITY preset, AAC `audio/mp4`; web `MediaRecorder`, Opus
  `audio/webm`), 10 min cap. `features/voice`: `VoiceNotes` list (opens the original on first
  play), `RecordingTile` (live timer + stop), `VoicePlayer` (Android `expo-audio`, web `<audio>`).
  Edit page: mic in the action bar, the list under it, remove per note (not the last item).
  Viewer: audio items leave the pager (page indicator counts photos and videos) and show as a
  list. **U-40** (Chromium fake microphone; written with the code). Android recording and AAC
  playback UNVERIFIED.
- **Phase 7 (D-100):** a public link uploads its own re-encrypted copies (spec 18), so it never
  depends on the entry's upload. The only blocker was spec 18 §create step 1 "synced
  (`baseRevision ≥ 1`)"; it is dropped, and the spec 18 test "refuses unsynced entries" became
  "creates a link for an entry that is still uploading" (programmer decision supersedes; red
  first). No "not ready yet" state is needed: the link exists only after its copies are uploaded.
  Account sharing (spec 17) still reports entries that are not synced yet.
- Screenshots regenerated (the script trashes its compose-screenshot entry instead of discarding the
  tray, since the edit page autosaves); version 0.3.0. Tag `v0.3.0` after the PR is merged.

