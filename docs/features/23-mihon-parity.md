# 23 — Mihon parity, power users, favorites (v0.2.0)

Status: draft 1 (2026-11-14, Opus, autopilot: the programmer delegated the decisions). Builds on
spec 22 (D-84). References: the programmer's Mihon screenshots of 2026-11-14 (library with count
badges, the Filter/Sort/Display/Group sheet, statistics cards, More, Edit categories, Data and
storage) and Mihon's source (`presentation/library/LibrarySettingsDialog.kt`,
`presentation/more/stats/StatsScreenContent.kt`, `presentation/category/CategoryScreen.kt`,
`presentation/more/settings/screen/Settings{Data,Advanced,Appearance,Library}Screen.kt`).

Rule: copy Mihon's patterns wherever they fit a photo journal; skip manga-only concepts
(chapters, sources, trackers, downloads queue, extensions). Every new behavior is a setting.

## 0. Decisions (autopilot)

| Topic | Decision |
|---|---|
| Photo viewer | Replace the hand-written pager and pinch zoom (spec 13) with `react-native-zoom-toolkit` 5.1.1 (MIT, Aug 2026; `Gallery` + `ResumableZoom`, built on Reanimated and Gesture Handler that are already installed). Video playback stays on `expo-video` (Android) and the media worker (web, spec 15) |
| Favorites | `favorite: boolean` added to `EntryMetadataV1` as an **optional** field (absent = false), so existing blobs stay valid. Indexed; tri-state filter; heart in the viewer action row; badge; bulk action; a "Favorites" pseudo-tab before the tag tabs. Risk: a client older than this spec drops the field when it edits an entry. Acceptable for a single-programmer deployment, and noted in the release notes |
| Library sheet | Rebuilt like Mihon: a bottom sheet with underlined tabs **Filter · Sort · Display · Group**. Display mode as chips, an items-per-row slider (own dot-track component, no new dependency), overlay (badge) checkboxes, tab options |
| Grouping | Group tab: **Day** (default), Month, Year, Tag, None |
| Header | Title with an item-count pill; tag tabs with count pills (Mihon "Show number of items"); count badge top-left on tiles |
| More | Logo header; switches **Hide shared journals** and **Pause sync** (Mihon "Incognito mode" analog, device setting); rows: Tags, Statistics, Data and storage, Import, Settings, About, Help |
| Tags editor | Mihon "Edit categories": reorderable list (drag handle; the order drives the tag tabs), rename and delete (applied to every own entry through the normal edit path, so it syncs), FAB **Add** (an empty tag stays in the order list until used) |
| Tags only in Settings (D-87) | Like Mihon categories: tags are **created, renamed, reordered and deleted only in the Tags editor**, which lives at Settings › **Tags** (Mihon Settings › Library › Categories); the More › Tags row opens the same screen. Compose, Edit, bulk "Add tag" and the gallery filter lose free-text tag typing: they open a **pick-from-list dialog** (checkboxes, Mihon "Set categories") with an **Edit tags** button that opens the editor and returns. The list = every tag on the user's own entries (synced through entries, so no migration and nothing lost) plus the editor's empty tags and order (stored as a device setting). Tags on entries shared with the user show read-only and are never added to the list. Found as a v0.1.3 issue (programmer 2026-11-14; `docs/planning/bugfix-v0.1.3.md` on branch `fix/v0.1.3-bugs`) |
| Statistics | Mihon cards: Overview, Media, Tags, Storage (device + server with a quota bar), Sync, Server, Account security |
| Server and keys | Settings › Account › **Server and keys**: server URL, version, API version, sign-up mode, feature flags, limits; account id, public-key fingerprint (security code), key-derivation parameters, encryption formats (XChaCha20-Poly1305 STREAM chunk size, sealed boxes), recovery-key confirmed, this device's key storage (biometric or not) |
| Data and storage | Mihon layout: storage location, **Backup and restore** (Create = export, spec 20; Restore = import, §7), automatic backup frequency (Off / Daily / Weekly; Android, runs when the app opens and is due), a storage usage bar (free / total), clear cache rows with "Used: …" |
| Advanced | New settings screen: show technical details, rebuild local index, verbose logging, reset settings to defaults, debug info (copyable) |
| Appearance additions | Pure black dark mode, 24-hour / 12-hour time |
| Import and encrypted export | Spec 20b scope (decided earlier: passphrase key, after spec 22): `.kdkx` parts, an import service for plain and encrypted exports, and the `decrypt-export` script |

## 1. Phases (each one commit, every test green; tag v0.2.0 at the end)

1. Viewer on `react-native-zoom-toolkit`.
2. Favorites (contract, core index and filter, client UI).
3. Library sheet rework, grouping, count pills, badges.
4. More rework, Pause sync, Tags editor, tags only in Settings (tag picker replaces `TagInput` in
   Compose, Edit, bulk actions and the filter sheet).
5. Statistics cards, Server and keys.
6. Data and storage (Mihon), Advanced, Appearance additions.
7. Import and encrypted export (spec 20b).
8. Remaining spec 22 phase 5 screens (calendar, shared, trash, links, admin, auth) on app bars.
9. Screenshots, version 0.2.0, tag.

## 2. Tests

Unit tests for every pure model (grouping, favorites filter, tag rename/order, statistics cards,
server facts, `.kdkx` format, import parser). Web UI flows: **U-26** favorites (heart → Favorites tab
shows it → filter excludes it), **U-27** grouping (Month shows month headers), **U-28** tags editor
(rename a tag → the tab shows the new name), **U-30** tag picker (Compose has no free-text tag
field; a tag added in Settings › Tags shows in the picker; pick it → saved entry has it), **U-29** export encrypted + import into a new account
→ same entries. Existing flows keep their test ids.

## 3. Implementation notes

- **Phase 1 (2026-11-14):**
  - Android: `features/viewer/media-pager.native.tsx` uses `react-native-zoom-toolkit`'s `Gallery`
    (`onUpdate` runs as a worklet and reports zoom with `runOnJS`). Photos render through
    `features/media/photo-surface.native.tsx`, a plain image with the old test id and
    zoomed/unzoomed label, because the Gallery does the zooming.
  - Web keeps the FlatList pager and `ZoomablePhoto` (`media-pager.tsx`, `photo-surface.tsx`):
    zoom-toolkit's web support is unverified, and the web flows (U-06, U-12, U-13) pass unchanged.
  - **UNVERIFIED on a device:** an APK build needs disk space this machine doesn't have (3.9 GB
    free, floor 4 GB). The CI release build compiles it; U-12 (Android) checks it in the batched
    Android round.
- **Phase 2 (2026-11-14):**
  - `EntryMetadataV1.favorite` (optional). `applyEntryEdit` and `mergeMetadata` (three-way: the
    side that changed it wins) write the field only when it is true, so blobs without favorites
    stay byte-compatible. `metadataEquivalent` now compares it, so a favorite-only edit counts as
    a change (found by the bulk test). The index carries `favorite`, and attributes gain
    `favorite`.
  - Entries service: `setFavorite`, `renameTag`, `deleteTag` on a shared `bulkEdit`. The latter
    two are for the phase 4 Tags editor.
  - Client: `galleryFilters.favorite` and `galleryBadges.favorite` use zod `.default`, so stored
    settings from before keep their other values. A heart badge; a heart action in the viewer
    (`viewer.favorite`); Add to/Remove from Favorites bulk actions; a Favorites pseudo-tab after
    All (`gallery.tagTab.favorites`, it drives the `favorite` filter).
  - U-26 was written after the code (no red run).
- **Phase 3 (2026-11-14):**
  - Grouping (`groupSections`, unit-tested red first): Day, Month (`m:`), Year (`y:`), Tag (`t:`,
    an entry appears under each of its tags, untagged entries last as `n:`), None. Setting
    `galleryGroupBy` (`galleryGroupByDay=false` still means none). Group headers come from
    `formatGroupHeader`.
  - The sheet is in Mihon's layout (`sheet-tabs.tsx`): underlined Filter · Sort · Display · Group
    tabs fixed above the scrolling content (`Sheet.header`); display modes as chips
    (`settings.displayMode.<mode>`, U-24 updated); items-per-row dot slider (`sheet.columns.N`,
    per orientation); badge and tab checkboxes; group list (`sheet.group.<key>`).
  - Count pills next to the gallery title (`gallery.count`) and on tag tabs when "Show number of
    items" is on.
  - U-27 was written after the code (no red run).
- **Phase 4 (2026-11-14):**
  - Settings `syncPaused` and `tagOrder`. Pause sync gates sync passes only; original eviction
    still applies, because the server holds the copies. Turning it off starts a sync.
  - More in Mihon's layout: app logo, switches (Hide shared journals, Pause sync; the latter is
    hidden in local mode), then Lock now, Tags, Statistics, Trash, Data and storage, then
    Settings, About, Help (README link).
  - Tags editor `/tags` (`TagsScreen`, `tag-order.ts` unit-tested red first): ordered list with
    move up/down, count, rename and delete (through `renameTag`/`deleteTag` on every own entry,
    so the change syncs), FAB Add (an empty tag stays in the order). Tag tabs follow `tagOrder`.
    Up/down buttons instead of a drag handle (no drag-and-drop dependency).
  - U-28 was written after the code; its first run failed on my own wrong position expectation
    (a rename keeps the tag's place), which was corrected.
- **Phase 5 (2026-11-14):**
  - The index carries `videoCount`, `videoMs` and `originalBytes` (optional, from the metadata
    the index already decrypts). The statistics model adds photos, videos, video length, journal
    size, favorites and the tag count (tests red first).
  - Statistics is now Mihon's card layout (Overview, Photos and videos, Journal, Storage, Server
    usage with a quota bar, Sync), plus the monthly and yearly charts and the top tags.
  - Server and keys (`/settings/server`, Settings › Account): server address, version and API,
    sign-up mode, sharing and links, limits, storage used; account id, username, security code,
    Argon2id parameters, recovery key state; key storage on this device and the encryption
    formats in use (`server-facts.ts`, unit-tested red first). Local mode shows only the account
    and device sections.
- **Phase 6 (2026-11-14):**
  - Appearance: `pureBlack` (dark background #000, surfaces #121212; the theme store and the Paper
    theme take the resolved colors) and `timeFormat` 24h/12h (`format-clock.ts`, tested red first;
    used in gallery captions).
  - Data and storage in Mihon's order: storage location, Backup and restore (Create backup =
    export; Restore comes in phase 7), storage usage bar from `adapters/device-storage` (Android
    `Paths` disk space, web `navigator.storage.estimate()`), cache rows with "Used: …".
  - Advanced (`/settings/advanced`): technical details (moved from Appearance), rebuild local
    index (clear + build, shows the count and time), reset settings to defaults, debug info as
    selectable JSON with no journal content. Searchable.
  - Not done from §0: automatic backup frequency (it needs import and encrypted export first;
    phase 7).
- **Phase 7 (2026-11-14), spec 20b scope:**
  - Core `export/kdkx.ts`: the `.kdkx` file is magic `KDKX`, version, Argon2id cost (u32 BE ops and
    memory), a 16-byte salt, a STREAM header, then 1 MiB XChaCha20-Poly1305 chunks. The writer
    holds back one chunk, so the last one is marked final at close; no total size is needed up
    front. The key is Argon2id(passphrase) with the account's start cost. Round-trip, wrong
    passphrase, tampering and truncation tests were red first.
  - Core `export/parse.ts`: a strict parser for our own `entry.md` (front-matter subset with flow
    lists and maps, quoted strings); tested as a round trip with the writer. The exporter now
    writes `favorite: true`.
  - Client: `encryptedDestination` wraps any export destination (parts become `name.zip.kdkx`).
    Export has an "Encrypt with a passphrase" switch (at least 12 characters, repeated).
    `journal-import.ts` opens zip and `.kdkx` parts, recreates every entry (time with offset,
    tags, place, note, media in order, favorite) through the normal import pipeline and a new
    `entries.createFromMedia` (the tray write path, extracted into `insertEntry`). It skips
    entries with the same time and number of items, and reports missing or refused files. File
    picking: `adapters/import-picker` (Android `File.pickFileAsync` multiple, web file input).
    Data and storage › Restore backup opens `/settings/import`.
  - `pnpm --filter @kodakiko/core decrypt-export <parts…>` (passphrase from
    `KODAKIKO_EXPORT_PASSPHRASE` or a prompt) writes the plain zips; checked end to end.
  - U-29 (encrypted export on web → import into a second account → 2 tiles); written after the
    code; its first run failed only on a missing second Back step in the test.
  - Not done: automatic backup frequency (needs a persisted folder grant on Android). Import
    unzips whole parts in memory (fine for normal journals; the web download fallback caps parts
    at 1 GB).
- **Phase 8 (2026-11-14):** every remaining text "Back" button (compose, trash, day, collection,
  share, links, admin, placeholder) and the calendar's ‹ › buttons are icon actions
  (`IconAction`, same test ids). Auth screens have no back navigation and keep labeled primary
  buttons.
- **D-87 tags only in Settings (2026-11-14):**
  - `features/tags/tag-picker.tsx`: chips (same `tags.chip.N`/`tags.remove.N` ids), a "Choose
    tags" button, and a checkbox dialog (`tags.option.<tag>`, limit `TAGS_PER_ENTRY_MAX`) whose
    "Edit tags" opens Settings › Tags. It replaces the free-text `TagInput` (deleted) in Compose,
    Edit and the filter sheet. Bulk add/remove tag picks from the same list
    (`bulk.tagOption.<tag>`). Settings › Tags row added; More › Tags opens the same screen.
  - Tests adapted to D-87 (the programmer's decision supersedes spec 10's free-text steps):
    entry-compose, U-06 and U-10 now create tags in the editor and pick them; the screenshot
    script creates its tags first. Gone with the feature: the "Already added." duplicate hint
    (duplicates can't be picked) and comma/Backspace typing; the chip and save assertions stay.

