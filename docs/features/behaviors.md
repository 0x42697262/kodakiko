# Behavior catalog (D-72)

Every user-visible behavior of Kodakiko, written as Given / When / Then, with the spec that builds it
and the test that proves it. A spec is done only when all rows that name it pass (rule 8 in
`README.md`; a `ui` flow counts only for the spec that 07c §8 assigns it to). Rows are written **before** the code (test first).

Test layers:

| Layer | Where | Runs in |
|---|---|---|
| `unit` | next to the code (`packages/*/test`, `apps/*/test`) | `pnpm verify` |
| `api` | `apps/server/test/integration/` (real Postgres + Garage) | `pnpm --filter @kodakiko/server test:integration` |
| `e2e` | `tests/e2e/behaviors/B-NNN.test.ts`: client **services** (memory adapters, real crypto) against a real in-process server | `pnpm --filter @kodakiko/e2e test` |
| `ui` | Playwright (web, Chromium desktop + Pixel 7 emulation) and Maestro (Android emulator) flows `U-NN` of spec 07c §8, against the local UI test stack | `pnpm ui:web`, `pnpm ui:android` |
| `manual` | the spec's verification checklist on an Android device and in Chrome | by hand; reported |

The `ui` column names the flow; its platforms are in 07c §8. Rows still `manual` are the ones
neither tool can drive (07c §8, last paragraph).

Each `e2e` test file starts with the row's Given/When/Then as its `describe`/`it` titles (strings,
not comments), so a failing test names the broken behavior.

## Accounts and security

| ID | Given | When | Then | Spec | Layer |
|---|---|---|---|---|---|
| B-001 | an admin invite link | a new user signs up with a username and a 12+ character password | the account exists, the 24 recovery words are shown, and the journal is empty and unlocked | 04, 07b | e2e + ui U-01 |
| B-002 | an invite already used | someone signs up with it | sign-up is refused with "invalid, used or expired" | 04 | api |
| B-003 | an account | the user signs in on a second device with the right password | the same journal keys open there | 04, 07b | e2e |
| B-004 | an account | someone signs in with a wrong password or an unknown username | both get the same "Username or password is incorrect." answer | 04 | api + ui U-02 |
| B-005 | a signed-in device | the user changes the password | other sessions are signed out and the new password unlocks everywhere | 04, 20 | e2e |
| B-006 | a forgotten password | the user enters the 24 words and a new password | the journal opens with the new password; old sessions end | 04, 07b | e2e + ui U-03 |
| B-007 | a server operator with database access | they read every table | no note, tag, location, file name or key appears in plaintext | 06, 21 | api (dump grep) |
| B-008 | an unlocked app | the auto-lock time passes in the background (Android) or idle (web) | the journal locks and nothing decrypted stays on screen or in memory | 16 | unit + ui U-14, U-15 |
| B-009 | a locked app | the user unlocks with fingerprint (Android) or password | the app returns to the screen it was on | 16 | ui U-04 (password) + manual (fingerprint) |
| B-010 | an account | the user requests deletion | the device signs out, sign-in offers "Keep My Account" until the due date, and after it all data is erased | 04, 06, 20 | e2e + ui U-05 |

## Capture and entries

| ID | Given | When | Then | Spec | Layer |
|---|---|---|---|---|---|
| B-020 | the capture screen | the user taps the shutter, holds it for a video, and picks 2 files | 4 items are in the tray, each already encrypted on the device | 09 | unit + ui U-06, U-09 |
| B-021 | a photo with EXIF date and GPS | it is imported | the entry time and location come from the photo | 09, 10 | unit |
| B-022 | a tray of media | the user adds a note and tags and saves while offline | the entry appears in the gallery at once and syncs when online | 10, 08 | e2e + ui U-06, U-07 |
| B-023 | an entry | the user edits note, tags, time, location and photo order | every device shows the edit after sync | 10, 13 | e2e |
| B-024 | the same entry edited on two offline devices | both sync | both edits survive (note by the later edit, tags merged) | 08 | e2e |
| B-025 | an entry | the user moves it to the trash, then restores it | it leaves and returns to the gallery on every device | 10 | e2e |
| B-026 | a trashed entry | 30 days pass | it is deleted on the server and on every device | 06 | api |
| B-027 | an entry with 3 photos | the user removes one | the entry keeps 2; removing the last one is refused | 10 | unit |
| B-028 | a web browser | the user pastes a screenshot or drops a file | it is added to the tray | 09 | ui U-08 |
| B-029 | a HEIC file in a browser that cannot read it | it is added | it is refused with the "use the Android app" message | 09 | unit |

## Browsing

| ID | Given | When | Then | Spec | Layer |
|---|---|---|---|---|---|
| B-040 | entries on several days | the user opens the gallery | entries are grouped by day, newest first | 11 | unit + ui U-10 |
| B-041 | tagged entries | the user filters by two tags, a date range and a search word | only entries matching all of them show | 11 | unit + ui U-10 |
| B-042 | an entry from the same date in an earlier year | the gallery opens | "On this day" shows it with "1 year ago" | 11 | unit |
| B-043 | entries in a month | the user opens the calendar | each day with entries shows its cover photo; tapping opens that day | 12 | unit + ui U-11 |
| B-044 | a photo in an entry | the user pinches or double-taps | it zooms, pans within its edges, and swiping changes photo only when not zoomed | 13 | unit + ui U-12 + manual (pinch) |
| B-045 | a note with markdown, a link and an image | the entry opens | the note renders; the link asks before opening; the image is never loaded | 13 | unit |
| B-046 | a long video | the user plays it on the web and seeks | it starts quickly and seeking works without downloading the whole file | 15 | unit + ui U-13 |
| B-047 | a video on Android | the user plays it | it plays and no decrypted file remains after closing or locking | 15 | manual |
| B-048 | a fresh second device | it syncs | every thumbnail appears and stays available offline | 14 | e2e |
| B-049 | a full cache | the user opens more originals | the oldest unpinned originals are removed; pinned ones stay offline | 14 | unit |

## Sync and storage

| ID | Given | When | Then | Spec | Layer |
|---|---|---|---|---|---|
| B-060 | a 500 MB video | it uploads on Android and the app goes to the background | the upload continues with a notification and resumes after a dropped connection | 08 | manual |
| B-061 | the Wi-Fi-only setting with a 100 MiB threshold | a larger file is captured on mobile data | it waits for Wi-Fi; smaller files upload | 14 | unit + manual |
| B-062 | a user at their quota | they save another entry | the upload stops with "Storage full" and Retry works after the quota is raised | 05, 08, 19 | e2e |
| B-063 | the server's old deletion records pruned | a device with an old cursor syncs | it resyncs and ends identical to the others | 06, 08 | e2e |
| B-064 | the storage bucket | its objects are read directly | each starts with `KDKS` and cannot be read without keys | 05, 21 | api |

## Sharing and links

| ID | Given | When | Then | Spec | Layer |
|---|---|---|---|---|---|
| B-080 | sharing turned on | A shares a journal with B and both compare the security code | B sees its entries read-only; the codes match | 17 | e2e + ui U-17 |
| B-081 | a shared journal | A removes an entry, then removes B | B loses that entry, then the whole journal | 17 | e2e |
| B-082 | a recipient | B leaves the journal | it disappears from B's devices | 17 | e2e + ui U-17 |
| B-083 | public links turned on | A creates a link choosing some photos and videos, with notes off | a browser without an account sees exactly those, without notes, location or EXIF/container dates | 18 | e2e + ui U-18 |
| B-084 | a one-view link | two people open it at the same time | exactly one sees it | 18 | api |
| B-085 | a link | A revokes it | the link stops working | 18 | api |
| B-086 | a public link with a video | the viewer plays and seeks it | it streams and seeks in the browser | 18 | ui U-18 |

## Settings, admin, export

| ID | Given | When | Then | Spec | Layer |
|---|---|---|---|---|---|
| B-100 | an admin | they create an invite, set a user's quota and role | the user can sign up and is limited by the quota; the last admin cannot be demoted | 19 | api + ui U-19 |
| B-101 | a member | they call an admin route | it answers "not found" | 19 | api |
| B-102 | a journal with photos, videos and notes | the user exports it | the zip parts contain every media file decrypted and one `entry.md` per entry with date, tags, location and note | 20 | e2e + ui U-20 |
| B-103 | an export in progress | the user locks the app | the unfinished part is removed and earlier parts stay | 20 | unit |
| B-104 | Android | the user changes the server in Settings | the device signs out and the welcome screen asks for the new server | 20 | ui U-21 |
| B-105 | the web app with "keep me unlocked" on | the browser restarts | the journal opens without a password; "Lock Now" turns it off | 16 | ui U-16 |
