# Behavior catalog

Every user-visible behavior of Kodakiko as Given / When / Then, with the tests that prove it.
Since v0.4.0 the app is the Kotlin Android app; behaviors that only existed in the web app are kept
below, marked **dropped**, so their ids are not reused (`docs/testing.md` lists the dropped flows).

| Layer | Where | Runs with |
|---|---|---|
| `unit` | Kotlin: `apps/android/**/src/test` (JVM, Robolectric, Compose UI and Roborazzi tests), `src/commonTest`/`jvmTest` of `:shared:*`; TypeScript: `apps/server/test`, `apps/link-viewer/test`, `packages/*/test` | `./gradlew verify` (in `apps/android`), `pnpm verify` |
| `api` | `apps/server/test/integration/` (real Postgres and Garage) | `pnpm --filter @kodakiko/server test:integration` |
| `e2e` | `apps/android/e2e`: the real Kotlin services of two simulated devices against the server on the test stack; a test named `BNNNTest` (or a `bNNN…` test method) repeats the row | `./gradlew e2e` |
| `device` | `src/androidTest` (Android modules) or `src/androidDeviceTest` (`:shared:*` modules), on the emulator | `./gradlew :module:connectedDebugAndroidTest` or `:module:connectedAndroidDeviceTest` |
| `ui` | Maestro flows (`tests/ui/android/flows`) and the link-viewer Playwright spec | `pnpm ui:android`, `pnpm ui:link-viewer` |
| `twin` | A Compose test that walks the same steps as a v0.3.1 web flow (the flow id is in the test name) | `./gradlew verify` |
| `manual` | an Android phone | by hand |

## Accounts and security

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-001 | an admin invite link | a new user signs up with a username and a 12+ character password | the account exists, the 24 recovery words are shown, and the journal is empty and unlocked | e2e `B001Test` + ui U-01 |
| B-002 | an invite already used | someone signs up with it | sign-up is refused with "invalid, used or expired" | api |
| B-003 | an account | the user signs in on a second device with the right password | the same journal keys open there | e2e (`SyncTest`, `LocalOnlyTest`) |
| B-004 | an account | someone signs in with a wrong password or an unknown username | both get the same "Username or password is incorrect." answer | api + e2e `AccountTest` + ui U-02 |
| B-005 | a signed-in device | the user changes the password | other sessions are signed out and the new password unlocks everywhere | e2e `AccountTest` |
| B-006 | a forgotten password | the user enters the 24 words and a new password | the journal opens with the new password; old sessions end | e2e `AccountTest` + ui U-03 |
| B-007 | a server operator with database access | they read every table | no note, tag, location, file name or key appears in plaintext | api (dump grep) |
| B-008 | an unlocked app | the auto-lock time passes in the background | the journal locks and nothing decrypted stays on screen or in memory | unit (`:service:lock`, `LockFenceTest`) + twin `AutoLockWiringTest` + ui U-15. The web half (idle lock in the browser) is **dropped** |
| B-009 | a locked app | the user unlocks with fingerprint, device PIN or password | the app returns to the screen it was on | unit (`LockViewModelTest`, `RouteGuardsTest`) + manual (fingerprint). The web half (U-04, reload) is **dropped** |
| B-010 | an account | the user requests deletion | the device signs out, sign-in offers "Keep My Account" until the due date, and after it all data is erased | e2e `AccountTest` + ui U-05 |

## Capture and entries

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-020 | the capture screen | the user taps the shutter, holds it for a video, and picks 2 files | 4 items are in the tray, each already encrypted on the device | unit (`:service:entries`) + twin `CaptureScreenTest` (U-06, U-09) + ui U-09 |
| B-021 | a photo with EXIF date and GPS | it is imported | the entry time and location come from the photo | unit (`:shared:media`, `:service:entries`) |
| B-022 | a tray of media | the user adds a note and tags and saves while offline | the entry appears in the gallery at once and syncs when online | e2e `B022Test` + twin `CaptureScreenTest` |
| B-023 | an entry | the user edits note, tags, time, location and photo order | every device shows the edit after sync | e2e `B023Test` |
| B-024 | the same entry edited on two offline devices | both sync | both edits survive (note by the later edit, tags merged) | e2e `B024Test` |
| B-025 | an entry | the user moves it to the trash, then restores it | it leaves and returns to the gallery on every device | e2e `B025Test` |
| B-026 | a trashed entry | 30 days pass | it is deleted on the server and on every device | api |
| B-027 | an entry with 3 photos | the user removes one | the entry keeps 2; removing the last one is refused | unit (`MediaViewModelTest`) + twin `EditFlowsTest` (U-36) |
| B-028 | a web browser | the user pastes a screenshot or drops a file | it is added to the tray | **dropped** with the web app (Android paste on the edit page: twin `EditFlowsTest` U-39) |
| B-029 | a HEIC file in a browser that cannot read it | it is added | it is refused with the "use the Android app" message | **dropped** with the web app |
| B-030 | the edit page with changes (v0.4.1) | the user presses Back | a sheet asks Save / Discard / Keep editing; Save writes the entry, Discard puts it back as it was (a new entry is deleted) and removes media added since it opened; locking the app still saves | unit (`EditViewModelTest`) + twin `EditFlowsTest` + `EditWiringTest` |
| B-031 | an entry saved with photos picked from the phone's gallery, Android 11+ (v0.4.1) | the app offers "Delete N from this device?" and the user chooses Delete | Android's own confirmation appears and only then are the originals deleted; Keep (default), Discard, a discarded tray or a lock delete nothing | unit (`PendingDeviceDeletesTest`, `AppDeviceOriginalsTest`, `DeviceMediaUrisTest`) + manual (system dialog) |

## Browsing

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-040 | entries on several days | the user opens the gallery | entries are grouped by day, newest first | unit (`:shared:index`, `GalleryViewModelTest`) + ui U-10 |
| B-041 | tagged entries | the user filters by two tags, a date range and a search word | only entries matching all of them show | unit + ui U-10 |
| B-042 | an entry from the same date in an earlier year | the gallery opens | "On this day" shows it with "1 year ago" | unit (`:shared:index`, `GalleryContentTest`) |
| B-043 | entries in a month | the user opens the calendar | each day with entries shows its cover photo; tapping opens that day; arrows and swipes move exactly one month (v0.4.1) | unit (`CalendarViewModelTest`, `CalendarContentTest`) + ui U-11 |
| B-044 | a photo in an entry | the user pinches or double-taps | it zooms, pans within its edges, and swiping changes photo only when not zoomed | unit (`:ui:feature:viewer`) + ui U-12 + manual (pinch) |
| B-045 | a note with markdown, a link and an image | the entry opens | the note renders; the link asks before opening; the image is never loaded | unit (`:shared:markdown`, `MarkdownViewTest`) + device `DeviceMarkdownTest` |
| B-046 | a long video | the user plays it on the web and seeks | it starts quickly and seeking works without downloading the whole file | **dropped** with the web app (U-13). Android seeking: device `DecryptingDataSourceDeviceTest`; public links: B-086 |
| B-047 | a video on Android | the user plays it, seeks, closes the viewer or locks | it plays straight from the encrypted file; no decrypted file is ever written | device `VideoPlayerDeviceTest`, `DecryptingDataSourceDeviceTest` (plaintext-file audit) |
| B-048 | a fresh second device | it syncs | every thumbnail appears and stays available offline | e2e (`SyncTest`) + unit (`ThumbnailCacheTest`) |
| B-049 | a full cache | the user opens more originals | the oldest unpinned originals are removed; pinned ones stay offline | unit (`OriginalCacheTest`) |
| B-050 | an entry with a caption on a photo (v0.4.1) | it opens in the viewer | the caption is drawn on its photo, not under the pager; tapping a photo shows it full screen and a tap closes it | unit (`EntryViewerContentTest`) + Roborazzi `viewer/full_entry` |

## Sync and storage

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-060 | a 500 MB video | it uploads on Android and the app goes to the background | the upload continues with a notification and resumes after a dropped connection | **not in v0.4.x**: the foreground upload service was not ported (D-168); uploads run while the app process is alive; an interrupted multipart upload resumes on the next sync pass (unit `UploadResumeWireTest`) |
| B-061 | the Wi-Fi-only setting with a 100 MiB threshold | a larger file is captured on mobile data | it waits for Wi-Fi; smaller files upload | unit (`:service:sync`) + manual |
| B-062 | a user at their quota | they save another entry | the upload stops with "Storage full" and Retry works after the quota is raised | e2e `SyncTest` (scenario 6) |
| B-063 | the server's old deletion records pruned | a device with an old cursor syncs | it resyncs and ends identical to the others | e2e `B063Test` |
| B-064 | the storage bucket | its objects are read directly | each starts with `KDKS` and cannot be read without keys | e2e `B064Test` + api |

## Sharing and links

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-080 | sharing turned on | A shares a journal with B and both compare the security code | B sees its entries read-only; the codes match | e2e `SharingBehaviorsTest` + twin `SharingFlowsTest` (U-17) |
| B-081 | a shared journal | A removes an entry, then removes B | B loses that entry, then the whole journal | e2e `SharingBehaviorsTest` |
| B-082 | a recipient | B leaves the journal | it disappears from B's devices | e2e `SharingTest` + twin `SharingFlowsTest` (U-17) |
| B-083 | public links turned on | A creates a link choosing some photos and videos, with notes off | a browser without an account sees exactly those, without notes, location or EXIF/container dates | e2e `B083Test` + twin `LinksFlowsTest` (U-18 creation) + ui U-18 (link viewer) |
| B-084 | a one-view link | two people open it at the same time | exactly one sees it | api + e2e `PublicLinksTest` |
| B-085 | a link | A revokes it | the link stops working | api + e2e `PublicLinksTest` + unit (`LinksListViewModelTest`) |
| B-086 | a public link with a video | the viewer plays and seeks it | it streams and seeks in the browser | ui U-18 (link viewer) |
| B-087 | one entry, only To apps possible (local journal, or sharing and links off) (v0.4.1) | the user taps Share | the Android share chooser opens directly with all the entry's media; originals that are only on the server are downloaded first | unit (`ShareSheetViewModelTest`, `ShareOutServiceTest`) |

## Settings, admin, export

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-100 | an admin | they create an invite, set a user's quota and role | the user can sign up and is limited by the quota; the last admin cannot be demoted | api + twin `AdminSettingsTest`, `AdvancedAboutAdminWiringTest` (U-19) |
| B-101 | a member | they call an admin route | it answers "not found" | api |
| B-102 | a journal with photos, videos and notes | the user exports it to a folder | the zip parts contain every media file decrypted and one `entry.md` per entry with date, tags, location and note | unit (`:service:backup`, `ExportSettingsTest`); the browser download path of U-20 is **dropped** (Android writes through the folder picker) |
| B-103 | an export in progress | the user locks the app | the unfinished part is removed and earlier parts stay | unit (`:service:backup`) |
| B-104 | Android | the user changes the server in Settings | the device signs out and the welcome screen asks for the new server | e2e `AccountTest` + twin `AccountWiringTest` + ui U-21 |
| B-105 | the web app with "keep me unlocked" on | the browser restarts | the journal opens without a password; "Lock Now" turns it off | **dropped** with the web app (U-16) |
| B-106 | an encrypted `.kdkx` backup of any size, made by v0.3.1 or later (v0.4.1) | the user restores it | it is read in 1 MiB slices (no whole-file read), every entry comes back, a wrong passphrase is refused | unit (`RestoreLargeBackupTest`, `RandomSourceTest`, `ImportFailureTest`, `ImportSettingsTest` U-29) |
| B-107 | the debug log on (v0.4.1) | the app runs, syncs, locks or crashes | the log records ids, counts, durations and error class names; note text, captions, tags, file names, URLs, passwords and keys never appear | unit (`DebugLogFormatTest`, `DebugLogFileTest`, `DebugLogViewModelTest`) |
| B-108 | Appearance (v0.4.1) | the user picks Light / System / Dark, a color theme or pure black | the whole app redraws in that theme and keeps it after a restart; older settings load as the default theme | unit (`ThemePresetsTest`, `ThemePresetSettingsTest`, `AppearanceAndGallerySettingsTest`) + Roborazzi `theme/*`, `appearance/*` |
