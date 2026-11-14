# Testing

## Commands

| Command | What it runs | Needs |
|---|---|---|
| `pnpm verify` | ESLint, TypeScript in every package, unit tests (core, contract, server, client, native upload, UI-test support, scripts) | nothing |
| `pnpm --filter @kodakiko/server test:integration` | Server against Postgres and Garage | test stack |
| `pnpm test:e2e` | `tests/e2e/scenarios`: sync, sharing, public links, tags, local-only mode with two simulated devices | test stack |
| `pnpm ui:web` | Every Playwright flow in `tests/ui/web/specs`, desktop and Pixel 7 viewports (about 15 minutes) | Docker; starts its own stack |
| `pnpm ui:android U-NN …` | Maestro flows in `tests/ui/android/flows` | running emulator; `UI_SKIP_BUILD=1` reuses the installed build |
| `pnpm --filter @kodakiko/ui-tests screenshots` | Seeds a demo journal and writes `docs/screenshots/*.png` | Docker, ImageMagick, ffmpeg |

Test stack: `docker compose -f infra/compose/docker-compose.test.yml up -d --wait`.

One web flow on one viewport:

```sh
cd tests/ui
pnpm exec playwright test -c web/playwright.config.ts U-29 --project=desktop
```

Failures keep a trace and a video under `tests/ui/reports/`.

## Rules

- A test is written before the code it checks and seen failing first.
- Existing tests are not deleted, skipped or loosened. When the programmer changes a behavior, the
  test changes with it and the area's feature doc says so.
- Web flows that need one viewport skip the other in `beforeAll` (`test.skip(...)`), and every flow
  seeds its own account (`uiUsername('U-NN', project, n)`), so flows do not share state.
- Flows must not log console errors or page errors: the fixture fails the test if they do.

## How the UI test stack works

- `pnpm ui:web` starts `infra/compose/docker-compose.ui.yml` (project `kodakiko-ui`, Postgres,
  Garage and Caddy, all data on tmpfs so every run starts empty), runs the API on the host at
  port 9582 and serves the web build through Caddy at `http://localhost:9580`. Plain HTTP on
  `localhost` is a secure context, so service workers, WebCrypto and OPFS work without
  certificates. The host firewall must let the Caddy container reach port 9582.
- Element ids come from `apps/client/src/keys/`; flows find elements only by id.
- Android flows reach the stack through the emulator's host alias `10.0.2.2` (`adb reverse` does
  not forward on the test emulator). "Offline" on Android stops the stack's Caddy container and
  starts it again. Run the Android runner with the system `adb`, outside `nix develop`.
- Maestro fills fields with `tapOn` + `inputText`, hides the keyboard before tapping fields that
  may sit under it, and force-stops the app before each flow.

## Where things are

| What | Path |
|---|---|
| Flow catalog | [below](#flow-catalog) |
| Behavior catalog (Given / When / Then) | [behaviors.md](behaviors.md) |
| Seeding accounts and entries through the API | `tests/ui/support/seed.ts` |
| Reading what the server stores | `tests/ui/support/server-state.ts` |
| Media fixtures | `tests/fixtures/media/` |

## Not covered by automated tests

These run only on a phone: background uploads, biometric unlock, camera capture and hold to
record, Android ripple, haptics and predictive back, dynamic color, share to apps, clipboard
paste, voice recording with `expo-audio`, crop with `expo-image-manipulator`, and key-derivation
timing. Each feature doc lists what is checked only on a phone.

## Flow catalog

`W` = web in both viewports, `W-d` = the desktop viewport only, `W (mobile)` = the Pixel 7
viewport only, `A` = Android. The Behavior column points into [behaviors.md](behaviors.md). The
spec files are `tests/ui/web/specs/U-NN-*.spec.ts` and `tests/ui/android/flows/U-NN-*.yaml`.

| Flow | Platform | Behavior | Steps (test ids) |
|---|---|---|---|
| U-01 | W, A | B-001 | `seedInvite` → web: `/invite#<token>` (Android: connect with the invite URL) → `signUp.username`, `signUp.password`, `signUp.confirm` → `signUp.submit` → wait `recoveryKey.word.1` (60 s) → read the 24 words → `recoveryKey.done` → `confirmRecoveryWords` (Android: `common/recovery-words.yaml`) → `recoveryKey.confirmSubmit` → `home.signedInAs` contains the username |
| U-02 | W, A | B-004 | `seedAccount` → `/sign-in`: wrong password → `errors.invalidCredentials`; unknown username → `errors.invalidCredentials` with the same text; right password → `home.signedInAs` |
| U-03 | W, A | B-006 | `seedAccount` → `signIn.forgot` → `recover.username`, `recover.words` (the words joined by spaces), `recover.newPassword`, `recover.confirm` → `recover.submit` → `home.signedInAs` → `home.signOut` → sign in with the new password works; with the old one → `errors.invalidCredentials` |
| U-04 | W | B-009 (web) | `seedAccount` → sign in → `page.reload()` → `lock.title` → `lock.password` wrong → `lock.wrongPassword`; right → `home.signedInAs` |
| U-05 | W, A | B-010 | `seedAccount` + `seedDeletionRequest` → sign in → `cancelDeletion.title` → `cancelDeletion.submit` → `home.signedInAs` |
| U-06 | W | B-020, B-022 | `seedAccount` → sign in → `gallery.capture` → `addFiles(photo-gps.jpg, video-20s.mp4)` → `capture.trayItem.1` visible → Next → note "Picnic", tag "family" → Save → `gallery.tile.0` → open → `viewer.pageIndicator` "1 / 2"; the location row is shown |
| U-07 | W | B-022 offline | as U-06 up to Save, but `context.setOffline(true)` before Save → `gallery.tilePending.0` → `context.setOffline(false)` → the dot disappears within 60 s → a second context signed in to the same account shows `gallery.tile.0` |
| U-08 | W | B-028 | `seedAccount` → sign in → `/capture` → `pasteFile(screenshot.png)` → `capture.trayItem.0`; `dropFile(photo-portrait.jpg)` on `capture.dropZone` → `capture.trayItem.1` |
| U-09 | A | B-020 | sign in → `gallery.capture` → tap `capture.shutter` → `capture.trayItem.0`; `capture.mode.video`, tap `capture.shutter`, wait, tap again (records a few seconds) → `capture.trayItem.1`; Next → Save → `gallery.tile.0`. Uses the emulator's virtual camera (UNVERIFIED for vision-camera: if the camera does not start, mark U-09 `manual` in `behaviors.md` and report) |
| U-10 | W, A | B-040, B-041 | `seedEntries`: 3 entries on 3 different past days, tags `a` / `a b` / `c`, one note contains "lake" → 3 day headers → Filter → tags `a`, `b` → Apply → exactly `gallery.tile.0` → Clear All → Search "lake" → exactly 1 tile |
| U-11 | W, A | B-043 | `seedEntries` on the 3rd and the 17th of the current month → Calendar tab → `calendar.day.<YYYY-MM-17>` → `/day/…` shows `gallery.tile.0` |
| U-12 | W-d, A | B-044 | `seedEntries` 1 entry with `photo-gps.jpg`, `photo-portrait.jpg` → open → `viewer.pageIndicator` "1 / 2". Android: `doubleTapOn: { id: viewer.zoomable.0 }` → label "Photo 1, zoomed" → `swipe` left on it → indicator still "1 / 2" → `doubleTapOn` → label "Photo 1" → `swipe` left → "2 / 2". Web desktop: `dblclick` on `viewer.zoomable.0` → `aria-label` "Photo 1, zoomed" → `dblclick` → "Photo 1"; Ctrl + `mouse.wheel(0, -300)` → zoomed; Escape key not involved; `ArrowRight` after un-zooming → "2 / 2". Pinch is covered by the zoom-math unit tests and by hand || 14 |
| U-13 | W-d | B-046 | `seedEntries` with `video-20s.mp4` → `context.route` delays bucket responses 250 ms (Chrome would otherwise buffer the whole fixture on localhost before the seek) → open → play → `video.player` → `page.evaluate` sets the `<video>` `currentTime = 15` → within 10 s `currentTime ≥ 15` and not paused; the bytes of responses from `localhost:9581` (counted with `context.on('response')`, which also sees service-worker requests in Chromium) are less than the object's ciphertext size |
| U-14 | W-d | B-008 (web) | `page.clock.install()` **before** the first `goto` → `seedAccount`, sign in → `page.clock.fastForward('16:00')` with no input → `lock.title` |
| U-15 | A | B-008 (Android) | part 1: sign in → Settings › Security → auto-lock 1 minute → `pressKey: Home`; `waitAfterMs: 70000`; part 2: `launchApp: { appId: com.x42697262.kodakiko, stopApp: false }` → `lock.title` |
| U-16 | W-d | B-105, B-008 | `chromium.launchPersistentContext(tempDir, { baseURL: 'http://localhost:9580' })` with `page.clock.install()` → sign in → Settings › Security keep unlocked on → fast-forward 16 minutes → still `gallery` (no idle lock, D-63) → close the context → relaunch with the same directory → the gallery without a password → "Lock Now" → `lock.title` |
| U-17 | W-d | B-080, B-082 | `seedAccount` A and B, `seedEntries` 2 for A, `seedShare` → context B: Shared tab → the journal with 2 tiles, no "Edit" in an entry; contexts A and B open the security code screens (B through Settings › Account, never `page.goto`, which reloads and locks the web app) → the texts are equal; B "Leave" → the journal disappears |
| U-18 | W-d | B-083, B-086 | `seedEntries` 1 entry with `photo-gps.jpg`, `video-gps.mov`, `video-20s.mp4` and a note → create a link: untick `video-gps.mov`, notes off → read the URL → a fresh context with no account opens it → exactly `linkViewer.item.0` and `.1`; no note text; the photo "Download" (`page.waitForEvent('download')`) yields a JPEG whose `readImageMetadata` (Node) has no date and no GPS; the video plays and seeks to 10 s |
| U-19 | W-d | B-100 | `seedAccount({ role: 'admin' })` → Settings › Admin → New Invite → URL shown → a second context signs up with it → People shows the new user → set quota 1 GB → the row shows it |
| U-20 | W-d | B-102 | `page.addInitScript` deletes `window.showDirectoryPicker` (forces the download fallback) → `seedEntries` 2 entries → Settings › Export → collect every `download` → unzip in Node with `fflate` → one `entry.md` per entry; media bytes equal the fixture files |
| U-21 | A | B-104 | sign in → Settings › Account → Change Server… → confirm → `welcome.serverLabel` visible and empty |
| U-22 | A | — | `launchApp clearState` → `welcome.local` → local setup (username, password, `localSetup.understand`) → recovery key confirm → capture a photo with the emulator camera → `gallery.tile.0` visible and no `gallery.tilePending.0` |
| U-23 | A | — | part 2 of U-22's device state (no `clearState`): sidecar seeds an invite → Settings › Account › Connect a Server… (`10.0.2.2` UI stack, invite link, password) → `connect.done`; sidecar checks the server has 1 entry for the username |
| U-24 | W-d | — | `seedEntries` a photo and a video → Filter sheet: `sheet.filter.video` include → Display tab: display mode List → 1 tile with the clip's note; lock and unlock → still 1 tile (settings persist) |
| U-25 | W | — | `seedEntries` 3 entries tagged beach, city, city → tag tabs All, beach, city; `gallery.tagTab.1` → 2 tiles; All → 3 |
| U-26 | W-d | — | 2 entries → open the second, `viewer.favorite` → Favorited; `gallery.tagTab.favorites` → 1 tile; All → 2; Filter sheet favorite include→exclude → 1 tile |
| U-27 | W-d | — | 3 entries in June and July → 3 day headers; sheet Group › Month → 2 headers, "July 2026" then "June 2026" |
| U-28 | W-d | — | tags beach (2 entries) and city → More › Tags: row 0 "beach, 2 entries"; rename to seaside → row 0 seaside; gallery tab 0 "seaside" shows 2 tiles |
| U-29 | W-d | — | account A (2 entries) exports with a passphrase through the download fallback → only `.kdkx` files; account B: Data and storage › Restore backup, passphrase, choose the files → "Imported 2 entries" → 2 tiles |
| U-30 | W-d | — | entries this month and 2 months ahead → Calendar › skips the empty month, then › is disabled; jump to next month → "No entries in that month yet." |
| U-31 | W-d | — | More › Tags clicked 5 times quickly → one Tags page; one Back → More |
| U-32 | W-d | — | `seedEntries` 1 entry → viewer ⋮ › Move to Trash → Snackbar `gallery.noticeTrashed` "Moved to trash." and no tile → `gallery.noticeUndo` → the tile is back, the Snackbar gone |
| U-33 | W (mobile) | — | Filter sheet open → a 30 px drag on the handle springs back → a 300 px drag closes the sheet |
| U-34 | W-d | — | seeded note → edit page shows it rendered (no text field) → `edit.editNote` → type, `toolbar.bold`, type → `**swim**` in the field → `compose.back` right away → reopen: the rendered note has the text (autosave flushed on leave) |
| U-35 | W-d | — | edit page ⋮ › Caption "Heron at dawn" → `edit.captionText.0` → back: `viewer.caption` shows it → gallery search "heron" → 1 tile |
| U-36 | W-d | — | entry with a photo and a video → ⋮ on page 2 › Set as Cover → viewer page 1 is the video (Play in view) → remove page 2 with the confirm → no page dots → removing the last item offers no confirm |
| U-37 | W (mobile) | — | ⋮ › Crop → drag the bottom-right handle → Apply → the server has one media item with a new id (the cropped copy replaced the original) |
| U-38 | W-d | — | video entry → viewer: `viewer.inlineVideo.0` plays muted and looped → mute button unmutes → Data and storage › Autoplay videos off → the viewer shows Play and no inline video |
| U-39 | W-d | — | viewer Share › To apps: both items checked, "Share 2 items" → uncheck one → "Share 1 item", the encryption note shows → edit page: paste `screenshot.png` → a third page and dot appear |
| U-40 | W-d | — | Chromium fake microphone → edit page mic → recording tile → stop after 2 s → `voice.tile.0` with "00:01"–"00:04", no page dots → viewer: page indicator "1 / 1", play the voice note → the audio element plays |

Not driven by either tool (checked by hand): fingerprint unlock, the background upload
notification and resume, Android decrypted-video cleanup, pinch gestures, Wi-Fi-only uploads.

