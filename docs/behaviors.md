# Behavior catalog

Every user-visible behavior of Kodakiko as Given / When / Then, with the layer that proves it.
`e2e` rows have a test file `tests/e2e/behaviors/B-NNN.test.ts` whose `describe`/`it` titles repeat
the row; `ui` rows name a flow from the [flow catalog](testing.md#flow-catalog); `manual` rows are
checked by hand on a phone.

| Layer | Where | Runs with |
|---|---|---|
| `unit` | `packages/*/test`, `apps/*/test` | `pnpm verify` |
| `api` | `apps/server/test/integration/` (real Postgres and Garage) | `pnpm --filter @kodakiko/server test:integration` |
| `e2e` | `tests/e2e/behaviors/`: client services with memory adapters and real crypto against an in-process server | `pnpm --filter @kodakiko/e2e test` |
| `ui` | Playwright and Maestro flows | `pnpm ui:web`, `pnpm ui:android` |
| `manual` | an Android phone and Chrome | by hand |

## Accounts and security

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-001 | an admin invite link | a new user signs up with a username and a 12+ character password | the account exists, the 24 recovery words are shown, and the journal is empty and unlocked | e2e + ui U-01 |
| B-002 | an invite already used | someone signs up with it | sign-up is refused with "invalid, used or expired" | api |
| B-003 | an account | the user signs in on a second device with the right password | the same journal keys open there | e2e |
| B-004 | an account | someone signs in with a wrong password or an unknown username | both get the same "Username or password is incorrect." answer | api + ui U-02 |
| B-005 | a signed-in device | the user changes the password | other sessions are signed out and the new password unlocks everywhere | e2e |
| B-006 | a forgotten password | the user enters the 24 words and a new password | the journal opens with the new password; old sessions end | e2e + ui U-03 |
| B-007 | a server operator with database access | they read every table | no note, tag, location, file name or key appears in plaintext | api (dump grep) |
| B-008 | an unlocked app | the auto-lock time passes in the background (Android) or idle (web) | the journal locks and nothing decrypted stays on screen or in memory | unit + ui U-14, U-15 |
| B-009 | a locked app | the user unlocks with fingerprint (Android) or password | the app returns to the screen it was on | ui U-04 (password) + manual (fingerprint) |
| B-010 | an account | the user requests deletion | the device signs out, sign-in offers "Keep My Account" until the due date, and after it all data is erased | e2e + ui U-05 |

## Capture and entries

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-020 | the capture screen | the user taps the shutter, holds it for a video, and picks 2 files | 4 items are in the tray, each already encrypted on the device | unit + ui U-06, U-09 |
| B-021 | a photo with EXIF date and GPS | it is imported | the entry time and location come from the photo | unit |
| B-022 | a tray of media | the user adds a note and tags and saves while offline | the entry appears in the gallery at once and syncs when online | e2e + ui U-06, U-07 |
| B-023 | an entry | the user edits note, tags, time, location and photo order | every device shows the edit after sync | e2e |
| B-024 | the same entry edited on two offline devices | both sync | both edits survive (note by the later edit, tags merged) | e2e |
| B-025 | an entry | the user moves it to the trash, then restores it | it leaves and returns to the gallery on every device | e2e |
| B-026 | a trashed entry | 30 days pass | it is deleted on the server and on every device | api |
| B-027 | an entry with 3 photos | the user removes one | the entry keeps 2; removing the last one is refused | unit |
| B-028 | a web browser | the user pastes a screenshot or drops a file | it is added to the tray | ui U-08 |
| B-029 | a HEIC file in a browser that cannot read it | it is added | it is refused with the "use the Android app" message | unit |

## Browsing

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-040 | entries on several days | the user opens the gallery | entries are grouped by day, newest first | unit + ui U-10 |
| B-041 | tagged entries | the user filters by two tags, a date range and a search word | only entries matching all of them show | unit + ui U-10 |
| B-042 | an entry from the same date in an earlier year | the gallery opens | "On this day" shows it with "1 year ago" | unit |
| B-043 | entries in a month | the user opens the calendar | each day with entries shows its cover photo; tapping opens that day | unit + ui U-11 |
| B-044 | a photo in an entry | the user pinches or double-taps | it zooms, pans within its edges, and swiping changes photo only when not zoomed | unit + ui U-12 + manual (pinch) |
| B-045 | a note with markdown, a link and an image | the entry opens | the note renders; the link asks before opening; the image is never loaded | unit |
| B-046 | a long video | the user plays it on the web and seeks | it starts quickly and seeking works without downloading the whole file | unit + ui U-13 |
| B-047 | a video on Android | the user plays it | it plays and no decrypted file remains after closing or locking | manual |
| B-048 | a fresh second device | it syncs | every thumbnail appears and stays available offline | e2e |
| B-049 | a full cache | the user opens more originals | the oldest unpinned originals are removed; pinned ones stay offline | unit |

## Sync and storage

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-060 | a 500 MB video | it uploads on Android and the app goes to the background | the upload continues with a notification and resumes after a dropped connection | manual |
| B-061 | the Wi-Fi-only setting with a 100 MiB threshold | a larger file is captured on mobile data | it waits for Wi-Fi; smaller files upload | unit + manual |
| B-062 | a user at their quota | they save another entry | the upload stops with "Storage full" and Retry works after the quota is raised | e2e |
| B-063 | the server's old deletion records pruned | a device with an old cursor syncs | it resyncs and ends identical to the others | e2e |
| B-064 | the storage bucket | its objects are read directly | each starts with `KDKS` and cannot be read without keys | api |

## Sharing and links

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-080 | sharing turned on | A shares a journal with B and both compare the security code | B sees its entries read-only; the codes match | e2e + ui U-17 |
| B-081 | a shared journal | A removes an entry, then removes B | B loses that entry, then the whole journal | e2e |
| B-082 | a recipient | B leaves the journal | it disappears from B's devices | e2e + ui U-17 |
| B-083 | public links turned on | A creates a link choosing some photos and videos, with notes off | a browser without an account sees exactly those, without notes, location or EXIF/container dates | e2e + ui U-18 |
| B-084 | a one-view link | two people open it at the same time | exactly one sees it | api |
| B-085 | a link | A revokes it | the link stops working | api |
| B-086 | a public link with a video | the viewer plays and seeks it | it streams and seeks in the browser | ui U-18 |

## Settings, admin, export

| ID | Given | When | Then | Tested by |
|---|---|---|---|---|
| B-100 | an admin | they create an invite, set a user's quota and role | the user can sign up and is limited by the quota; the last admin cannot be demoted | api + ui U-19 |
| B-101 | a member | they call an admin route | it answers "not found" | api |
| B-102 | a journal with photos, videos and notes | the user exports it | the zip parts contain every media file decrypted and one `entry.md` per entry with date, tags, location and note | e2e + ui U-20 |
| B-103 | an export in progress | the user locks the app | the unfinished part is removed and earlier parts stay | unit |
| B-104 | Android | the user changes the server in Settings | the device signs out and the welcome screen asks for the new server | ui U-21 |
| B-105 | the web app with "keep me unlocked" on | the browser restarts | the journal opens without a password; "Lock Now" turns it off | ui U-16 |
