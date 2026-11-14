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
4. More rework, Pause sync, Tags editor.
5. Statistics cards, Server and keys.
6. Data and storage (Mihon), Advanced, Appearance additions.
7. Import and encrypted export (spec 20b).
8. Remaining spec 22 phase 5 screens (calendar, shared, trash, links, admin, auth) on app bars.
9. Screenshots, version 0.2.0, tag.

## 2. Tests

Unit tests for every pure model (grouping, favorites filter, tag rename/order, statistics cards,
server facts, `.kdkx` format, import parser). Web UI flows: **U-26** favorites (heart → Favorites tab
shows it → filter excludes it), **U-27** grouping (Month shows month headers), **U-28** tags editor
(rename a tag → the tab shows the new name), **U-29** export encrypted + import into a new account
→ same entries. Existing flows keep their test ids.

## 3. Implementation notes

(Filled in as the phases land.)
