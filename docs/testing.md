# Testing

## Commands

| Command | What it runs | Needs |
|---|---|---|
| `./gradlew verify` (in `apps/android`) | Spotless formatting, `tooling-tests` (no comments, no magic values, no literal UI text, module boundaries, catalog allowlist, release build rules), every JVM and Robolectric unit test of `:shared:*`, `:data:*`, `:platform:*`, `:service:*`, `:ui:*` and `:app` (debug, release and preview variants), the Roborazzi screenshot comparisons, and `:app:lintRelease` | JDK 21, Android SDK, host libsodium for Robolectric (F-KT-24) |
| `./gradlew e2e` (in `apps/android`) | `:e2e`: the Kotlin services of two simulated devices against the real server (sync, sharing, public links, tags, local-only mode, accounts, the e2e B-ids) | test stack, Node |
| `./gradlew :module:connectedDebugAndroidTest` / `:shared:x:connectedAndroidDeviceTest` | Device tests (`src/androidTest`, `src/androidDeviceTest`): camera, audio, decrypting video source, secure stores, markdown regex engine | a running emulator |
| `pnpm verify` | ESLint, TypeScript and unit tests of the server, contract, core, link viewer, UI-test support and scripts; the generated Android strings and license list (`check:generated`); license gate | nothing |
| `pnpm --filter @kodakiko/server test:integration` | Server against Postgres and Garage | test stack |
| `pnpm ui:android U-NN …` | Maestro flows in `tests/ui/android/flows` against the Kotlin debug build (`com.x42697262.kodakiko.next`; the runner builds `:app:assembleDebug`, installs it, and refuses the v0.3.1 id) | running emulator; `UI_SKIP_BUILD=1` reuses the installed build |
| `pnpm ui:link-viewer` | The public link viewer flow (U-18 viewer half) in Playwright, desktop and phone viewports | Docker; starts its own stack |

Test stack: `docker compose -f infra/compose/docker-compose.test.yml up -d --wait`.

One Gradle test class: `./gradlew :ui:feature:edit:testDebugUnitTest --tests '*EditViewModelTest'`.

### Screenshots (Roborazzi)

Screenshot tests render the stateless `XContent` composables on Robolectric at 360 × 640 dp
(`w360dp-h640dp-xhdpi`) and live next to each UI module in `src/test/screenshots/<area>/<name>.png`.
A normal test run compares against the committed baseline and fails on a difference; a missing
baseline is recorded on the first run. To re-record after an intended change:

```sh
./gradlew :ui:feature:settings:recordRoborazziDebug
```

Look at every re-recorded image before committing it, and say in the commit which change it
shows. `docs/screenshots/` holds copies of a curated set ([screenshots/README.md](screenshots/README.md)).

### Compose compiler reports

```sh
./gradlew :app:assembleRelease -Pkodakiko.composeReports=true
```

Off by default. With the property, every module that applies `kodakiko.compose` (`:app` and all
`:ui:*`) writes its Compose compiler reports and metrics to `<module>/build/compose-reports`
(composables, classes, `module.json`), and those modules compile non-incrementally so the reports are
complete. `apps/android/stability_config.conf` marks immutable models from non-Compose modules as
stable in every build. Four unstable parameters remain on purpose (default-argument ViewModels and
the fast-scroller thumb position).

### Maestro setup

- `pnpm --filter @kodakiko/ui-tests install:maestro` installs Maestro 2.12.1 (checked against its
  SHA-256); `tests/ui/android/create-avd.sh` creates the test emulator.
- Flows reach the UI test stack through the emulator's host alias `10.0.2.2` (`adb reverse` does
  not forward on the test emulator). "Offline" on Android stops the stack's Caddy container and
  starts it again. Run the runner with the system `adb`, outside `nix develop`.
- Element ids are the Compose `testTag`s from `TestTags` (exposed as resource ids); flows find
  elements only by id. Maestro fills fields with `tapOn` + `inputText`, hides the keyboard before
  tapping fields that may sit under it, and force-stops the app before each flow. Double taps use
  `delay: 250` and swipes run from 90 % to 10 % of the width (Compose gesture thresholds).

## Rules

- A test is written before the code it checks and seen failing first.
- Existing tests are not deleted, skipped or loosened. When the programmer changes a behavior, the
  test changes with it and the area's feature doc says so. A screenshot baseline is re-recorded only
  for an intended visual change.
- Every Maestro flow seeds its own account, so flows do not share state.

## Where things are

| What | Path |
|---|---|
| Flow catalog | [below](#flow-catalog) |
| Behavior catalog (Given / When / Then) | [behaviors.md](behaviors.md) |
| Compose tests and screenshot baselines | `apps/android/ui/**/src/test`, `apps/android/app/src/test` |
| End-to-end tests and their Node launcher | `apps/android/e2e`, `tests/e2e/support` |
| Seeding accounts and entries through the API | `tests/ui/support/seed.ts` |
| Reading what the server stores | `tests/ui/support/server-state.ts` |
| Media fixtures, v0.3.1 device fixtures, golden files | `tests/fixtures/media/`, `tests/fixtures/v031-device/`, `tests/fixtures/golden*/` |

## Not covered by automated tests

These run only on a phone: biometric unlock and the device-PIN prompt, camera capture and hold to
record on real hardware, voice recording quality (AAC channels and bit rate), Android's delete
confirmation for device originals, the system share chooser, clipboard paste, folder writes through
the system folder picker, haptics, dynamic color, and key-derivation timing. The one-time adoption
of a v0.3.1 install is a release drill (U-41) on an emulator with a real v0.3.1 install. There is no
background upload service yet, so long uploads with the app closed are not tested. Each feature doc
lists what is checked only on a phone.

## Flow catalog

The flows come from v0.3.1, where most ran in Playwright against the web app. Since v0.4.0:

- `A`: a Maestro flow on the Android emulator (`tests/ui/android/flows/U-NN-*.yaml`).
- `twin`: a Compose test in `./gradlew verify` that walks the same steps on the Kotlin screens; the
  test method's name starts with the flow id (`u17…`, `U19…`) where one exists.
- `LV`: the public link viewer's Playwright spec (`tests/ui/web/specs/U-18-link-viewer.spec.ts`).
- **dropped**: the behavior only existed in the web app (B-ids marked dropped in
  [behaviors.md](behaviors.md)).

| Flow | Runs as | Behavior | Steps (test ids) / where |
|---|---|---|---|
| U-00 | A | — | smoke: `launchApp clearState` → `welcome.title` |
| U-01 | A | B-001 | `seedInvite` → connect with the invite URL → `signUp.username`, `signUp.password`, `signUp.confirm` → `signUp.submit` → wait `recoveryKey.word.1` (60 s) → read the 24 words → `recoveryKey.done` → `common/recovery-words.yaml` → `recoveryKey.confirmSubmit` → `home.signedInAs` contains the username |
| U-02 | A | B-004 | `seedAccount` → sign in: wrong password → `errors.invalidCredentials`; unknown username → `errors.invalidCredentials` with the same text; right password → `home.signedInAs` |
| U-03 | A | B-006 | `seedAccount` → `signIn.forgot` → `recover.username`, `recover.words` (the words joined by spaces), `recover.newPassword`, `recover.confirm` → `recover.submit` → `home.signedInAs` → `home.signOut` → sign in with the new password works; with the old one → `errors.invalidCredentials` |
| U-04 | **dropped** | B-009 (web) | web reload locks the page; Android password unlock is covered by `LockViewModelTest` and U-15 |
| U-05 | A | B-010 | `seedAccount` + `seedDeletionRequest` → sign in → `cancelDeletion.title` → `cancelDeletion.submit` → `home.signedInAs` (screen logic also in `CancelDeletionViewModelTest`) |
| U-06 | twin | B-020, B-022 | `CaptureScreenTest.u06…`: two picked files fill the tray and Next opens the new entry |
| U-07 | e2e | B-022 offline | covered by `B022Test` (save offline, the entry shows at once and syncs when online) |
| U-08 | **dropped** | B-028 | web paste and drag-and-drop |
| U-09 | A + twin | B-020 | sign in → `gallery.capture` → tap `capture.shutter` → `capture.trayItem.0`; `capture.mode.video`, tap `capture.shutter`, wait, tap again → `capture.trayItem.1`; Next → Save → `gallery.tile.0` (emulator virtual camera); twin `CaptureScreenTest.u09…` |
| U-10 | A | B-040, B-041 | `seedEntries`: 3 entries on 3 different past days, tags `a` / `a b` / `c`, one note contains "lake" → 3 day headers → `gallery.filter` → `tags.choose` → tags `a`, `b` → `filter.apply` → exactly `gallery.tile.0` → `gallery.clearAll` → `gallery.search` "lake" → exactly 1 tile |
| U-11 | A | B-043 | `seedEntries` on the 3rd and the 17th of the current month → `tabs.calendar` → `calendar.day.<YYYY-MM-17>` → `day.screen` shows `gallery.tile.0` only |
| U-12 | A | B-044 | `seedEntries` 1 entry with `photo-gps.jpg`, `photo-portrait.jpg` → open → `viewer.pageIndicator` "1 / 2" → `doubleTapOn: { id: viewer.zoomable.0 }` → label "Photo 1, zoomed" → swipe left on it → indicator still "1 / 2" → `doubleTapOn` → label "Photo 1" → swipe left → "2 / 2". Pinch is covered by the zoom unit tests and by hand |
| U-13 | **dropped** | B-046 | web video seek; Android seeking is a device test (`DecryptingDataSourceDeviceTest`) |
| U-14 | **dropped** | B-008 (web) | idle auto-lock in the browser |
| U-15 | A + twin | B-008 | part 1: sign in → Settings › Security and privacy → auto-lock 1 minute → `pressKey: Home`; `waitAfterMs: 70000`; part 2: `launchApp` without stopping → `lock.title`; twins `AutoLockWiringTest.u15…`, `SecuritySettingsTest` |
| U-16 | **dropped** | B-105 | keep me unlocked in the browser |
| U-17 | twin | B-080, B-082 | `SharingFlowsTest.u17…`: the owner finds the recipient and both read the same security code; the recipient browses the journal read-only and leaving removes it |
| U-18 | twin + LV | B-083, B-086 | creation: `LinksFlowsTest.u18…`, `LinkCreateViewModelTest.u18…` (untick the `.mov`, notes off, the URL shows); viewing: LV spec on the link-viewer stack (exactly the chosen items, no note, downloads without date or GPS, the video plays and seeks) |
| U-19 | twin | B-100 | `AdminSettingsTest`, `AdvancedAboutAdminWiringTest.U19…`: New Invite shows the URL, the newcomer gets a 1 GB quota |
| U-20 | **dropped** (mechanism) | B-102 | the browser download fallback; Android exports through the system folder picker (`ExportSettingsTest`) |
| U-21 | A + twin | B-104 | sign in → Settings › Account › Change Server… → confirm → `welcome.serverLabel` visible and empty; twins `AccountWiringTest`, `AccountSettingsTest` |
| U-22 | A | — | `launchApp clearState` → `welcome.local` → local setup (username, password, `localSetup.understand`) → recovery key confirm → capture a photo with the emulator camera → `gallery.tile.0` with its `gallery.tilePending.0` badge (local entries stay queued until a server is connected) |
| U-23 | A | — | part 2 of U-22's device state (no `clearState`): a sidecar seeds an invite → Settings › Account › Connect a Server… (`10.0.2.2` UI stack, invite link, password) → `connect.done` |
| U-24 | twin | — | display mode List and a video-only filter persist (`GallerySheetsContentTest`, `GalleryViewModelTest`) |
| U-25 | twin | — | tag tabs All / beach / city filter the grid (`GalleryContentTest`, `GalleryViewModelTest`) |
| U-26 | twin | — | Favorites tab and the favorite include/exclude filter (`GalleryContentTest`, `GalleryViewModelTest`) |
| U-27 | twin | — | Group › Month shows month headers newest first (`GallerySheetsContentTest`, `GalleryViewModelTest`) |
| U-28 | twin | — | `TagsFlowsTest.u28…`, `TagsViewModelTest.u28…`: renaming a tag renames it on every entry and keeps its place |
| U-29 | twin | — | `ImportSettingsTest.u29…`: an encrypted export restores with its passphrase into another account |
| U-30 | twin | B-043 | the next-month arrow skips empty future months and stops after the last one; Jump to an empty month shows "No entries in that month yet." (`CalendarViewModelTest`, `CalendarContentTest`) |
| U-31 | twin | — | `CaptureAndTagsWiringTest.u31…`: opening the tag editor five times quickly opens it once and one Back leaves it |
| U-32 | twin | — | Move to Trash shows the "Moved to trash." snackbar and Undo brings the tile back (`TrashDayAndSnackbarContentTest`, `GalleryViewModelTest`) |
| U-33 | twin | — | a short drag on a sheet springs back, past 30 % or a fast fling closes it (`AdaptiveSheetTest`) |
| U-34 | twin | — | `EditFlowsTest.u34…`, `NoteViewModelTest`: the full-screen note editor writes markdown with the toolbar and the note is stored on Save (v0.4.1: no autosave on leave) |
| U-35 | twin | — | `EditFlowsTest.u35…`: a caption shows on its photo and is stored (and found by search) |
| U-36 | twin | B-027 | `EditFlowsTest.u36…`: Set as Cover moves an item first, removing asks first, the last item cannot be removed |
| U-37 | twin | — | `EditFlowsTest.u37…`: cropping replaces the photo with the cropped copy |
| U-38 | twin | — | inline video plays muted and looped, unmute works, Autoplay off shows Play instead (`InlineVideoViewModelTest`, `StorageSettingsTest`); v0.4.1: no Play button while a video autoplays |
| U-39 | twin | — | `ShareSheetContentTest`, `ShareSheetViewModelTest`, `SharingFlowsTest.u39…`: the To apps picker starts with every item checked and counts them; `EditFlowsTest.u39…`: pasting a picture adds a page and a dot |
| U-40 | twin | — | `EditFlowsTest.u40…`: a voice note recorded on the edit page is saved and listed |

Not driven by any tool (checked by hand): fingerprint unlock, Android decrypted-file audit on a real
phone, pinch gestures, Wi-Fi-only uploads, the delete-from-device confirmation.
