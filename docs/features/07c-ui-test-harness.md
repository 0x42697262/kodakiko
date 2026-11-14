# 07c — UI test harness: Playwright (web) and Maestro (Android)

Status: draft 1 (2026-11-11).

Goal: screen-level behaviors from `behaviors.md` run automatically against a real local server —
web in Chromium through Playwright, Android on an emulator through Maestro — so every later screen
spec can write its UI flows **before** its screens (D-72, D-74). This spec builds the harness, the
element-id rule, the media fixtures and one smoke flow per platform; the flow catalog (§8) lists
every later flow with the spec that makes it pass.

Read first: `features/behaviors.md`; `features/README.md` rule 8; specs 07a (app shell, primitives,
`keys/test-ids.ts`, server URL), 04 (CLI `create-invite`), 05 §9 (Garage bootstrap for tests);
facts F-TOOL-14..17, F-LIB-28; decisions D-72..D-75. Depends on 04, 05, 07a. Phase 1 (D-73): runs on the programmer's machine only;
CI comes in phase 2 (spec 21 §5).

## 1. Tools (allowlisted, `docs/dependencies.md` §Test tooling)

| Tool | Version | Where |
|---|---|---|
| `@playwright/test` | 1.64.1 | devDependency of `tests/ui`; browsers installed with `pnpm --filter @kodakiko/ui-tests exec playwright install chromium` (the Chromium build matching 1.64.1) |
| Maestro CLI | 2.12.1 | not an npm package: `tests/ui/android/install-maestro.sh` downloads `https://github.com/mobile-dev-inc/maestro/releases/download/cli-2.12.1/maestro.zip`, checks SHA-256 `54dbb0716425626604973cbb374c585ca30272375d9ce15177a1152765ddd9d7` (stored in `tests/ui/android/maestro.sha256`), unpacks to `~/.maestro-kodakiko/2.12.1/`; needs Java 17+ |
| ffmpeg | ≥ 8.0 on the developer machine | only for `tests/fixtures/media/generate.sh` (§6); never a runtime or CI dependency |
| Android emulator | SDK command-line tools; system image `system-images;android-36;google_apis;x86_64` | AVD `kodakiko-ui` created by `tests/ui/android/create-avd.sh` (`avdmanager create avd -n kodakiko-ui -k "<image>" -d pixel_7`) |

Missing ffmpeg, Java, `adb` or the emulator → the scripts print what is missing and exit 1; Sonnet
then stops and asks the programmer (no installs outside these scripts).

## 2. Element ids (`apps/client/src/keys/test-ids.ts`)

Both tools find elements by React Native `testID`: Maestro's `id:` selector matches it (F-TOOL-14),
react-native-web renders it as `data-testid`, which Playwright's `getByTestId` uses (F-TOOL-15).
One deterministic naming rule, so no spec has to invent ids:

1. An element that shows or is labeled by a text-catalog string gets `testID` = that **text key**
   (`welcome.continue`, `signUp.username`, `lock.unlockPassword`). Placement for the primitives:
   `TextField` puts the `testID` on its `TextInput` only (its label `Text` gets none; Playwright's
   `fill()` needs the input); its error text gets `testID` = the error's text key; `Banner` puts the
   key of its message on its container; `Button` on its pressable.
2. Repeated items get `<base>.<n>`: `n` is the 0-based position in display order unless the row
   says otherwise (`gallery.tile.0`; `recoveryKey.word.1` uses the 1-based word position).
3. Elements with no text (camera shutter, a photo page, a calendar cell) use the ids in the table
   below; specs adding such elements add their rows here.

Text keys this spec adds to other specs so every element a flow touches has a key: 07b
`signIn.username` "Username", `signIn.password` "Password", `recover.username` "Username",
`recover.confirm` "Confirm new password", `lock.password` "Password", `signUp.invite` "Invite link";
07a `home.signOut` "Sign Out", `invite.title` "Opening invite…". A failed sign-in (`401
INVALID_CREDENTIALS`) shows `errors.invalidCredentials`; a wrong password on `/lock` shows
`lock.wrongPassword`.

`test-ids.ts` exports `testId(key: TextKey): string` (identity, typed so renamed keys break the
build), `listTestId(base: string, n: number): string` and the frozen `TEST_IDS` map of rule 3:

| `TEST_IDS` name | Value | Element | Spec |
|---|---|---|---|
| `galleryTile` | `gallery.tile` (+ `.n`) | gallery tile | 11 |
| `galleryTilePending` | `gallery.tilePending` (+ `.n`) | the "not synced" dot of tile n | 11 |
| `galleryFab` | `gallery.capture` | the + button | 11 |
| `captureShutter` | `capture.shutter` | shutter | 09 |
| `captureTrayItem` | `capture.trayItem` (+ `.n`) | tray thumbnail | 09 |
| `captureFileInput` | `capture.fileInput` | the hidden web `<input type="file">` (set on the DOM element) | 09 |
| `captureDropZone` | `capture.dropZone` | the web drop target | 09 |
| `viewerPage` | `viewer.page` (+ `.n`) | media pager page | 13 |
| `viewerPageIndicator` | `viewer.pageIndicator` | the "2 / 5" text | 13 |
| `viewerZoomable` | `viewer.zoomable` (+ `.n`) | zoomable photo container; its `accessibilityLabel` is `viewer.photoLabel` "(n) => `Photo ${n}`" when not zoomed and `viewer.photoZoomedLabel` "(n) => `Photo ${n}, zoomed`" when zoomed (both added to spec 13's catalog) | 13 |
| `videoPlayer` | `video.player` | the video view of `/play/…` | 15 |
| `calendarDay` | `calendar.day` (+ `.YYYY-MM-DD`) | a day cell | 12 |
| `recoveryKeyWord` | `recoveryKey.word` (+ `.position`) | the `Text` holding only the word at that position (the number is a separate `Text`) | 07b |
| `recoveryKeyConfirmLabel` | `recoveryKey.confirmLabel` (+ `.n`) | label of confirm input n (0-2), text `recoveryKey.confirmWord` ("Word 7") | 07b |
| `recoveryKeyConfirmField` | `recoveryKey.confirmField` (+ `.n`) | confirm input n (0-2) | 07b |
| `placeholderSignedIn` | `home.signedInAs` | 07a's placeholder "Signed in as …" text | 07a |
| `linkViewerItem` | `linkViewer.item` (+ `.n`) | public viewer grid item | 18 |

The `Button`, `TextField` and `Banner` primitives (07a) take `testID` as a required prop; `Banner`
uses the key of its message. ids are present in every build (they cost nothing and contain no
user data).

## 3. Local UI test stack

`infra/compose/docker-compose.ui.yml`, project name `kodakiko-ui`, all data on `tmpfs` (each run
starts empty):

| Service | Notes |
|---|---|
| `postgres-ui` | same pinned `postgres:17`; env `POSTGRES_USER=kodakiko`, `POSTGRES_PASSWORD=kodakiko`, `POSTGRES_DB=kodakiko`; port `127.0.0.1:55433:5432`; healthcheck `pg_isready -U kodakiko` every 2 s |
| `garage-ui` | same pinned Garage (`dxflrs/garage:v2.5.0`, digest); `infra/compose/ui/garage.toml` (copy of `infra/compose/test/garage.toml`) mounted at `/etc/garage.toml`; port `127.0.0.1:9581:3900` (S3 directly, path style) |
| `caddy-ui` | pinned `caddy:2`; `infra/compose/ui/Caddyfile` mounted at `/etc/caddy/Caddyfile` (the image's default command reads it, and `import snippets/…` resolves relative to it); `infra/caddy/snippets` at `/etc/caddy/snippets` and `apps/client/dist` at `/srv/web` (both read-only); port `127.0.0.1:9580:9580`; env `S3_PUBLIC_ORIGIN=http://localhost:9581`, `CSP_SCRIPT_HASHES=` (empty); `extra_hosts: ["host.docker.internal:host-gateway"]` |

Plain HTTP on `localhost` on purpose: browsers treat `http://localhost` as a secure context
(service workers, WebCrypto, OPFS work) without certificates, and Android reaches it through
`adb reverse` (07a §1 allows cleartext to `localhost`, `127.0.0.1` and `10.0.2.2` in debug builds
only). The API listens on `0.0.0.0:9582` so the Caddy container reaches it through
`host.docker.internal`; a host firewall must allow that (the runner's first request to
`/health/ready` through Caddy fails with a clear message otherwise).

`infra/compose/ui/Caddyfile` (blocks open on their own line, F-TOOL-17):

```
{
  auto_https off
}

http://localhost:9580 {
  handle /api/* {
    reverse_proxy host.docker.internal:9582
  }
  handle /health/* {
    reverse_proxy host.docker.internal:9582
  }
  handle {
    import snippets/security-headers.caddy
    root * /srv/web
    try_files {path} /index.html
    file_server
  }
}
```

The security-header snippet is the **production** one (`infra/caddy/snippets/security-headers.caddy`,
created by this spec with the exact content of spec 21 §2.2), so the CSP is enforced in every UI
test run.

API and server CLI on the host, always started as `node --env-file=apps/server/.env.ui
apps/server/dist/{main,cli}.js` (Node's `--env-file` does not override variables already in the
environment, so the S3 keys passed by the runner win). `apps/server/.env.ui` is committed (test-only
values, no real secret): `PORT=9582`, `HOST=0.0.0.0`, `PUBLIC_BASE_URL=http://localhost:9580`,
`REQUIRE_HTTPS=false`, `RATE_LIMITS_ENABLED=false` (D-75: every request arrives from one address,
and the suite signs in hundreds of times), `TRUSTED_PROXIES=loopback,uniquelocal`,
`DATABASE_URL=postgres://kodakiko:kodakiko@127.0.0.1:55433/kodakiko`, `STORAGE_PROVIDER=garage`,
`S3_ENDPOINT=http://127.0.0.1:9581`, `S3_PUBLIC_ENDPOINT=http://localhost:9581`,
`S3_BUCKET=kodakiko-ui`, `SIGNUP_MODE=invite`,
`FAKE_PARAMS_SECRET=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` (base64url of the bytes 0-31),
`FLAG_SHARING=true`, `FLAG_PUBLIC_LINKS=true` (settings that do not exist yet are ignored by the
loader). Any other required setting added by a later spec is added to this file by that spec.

`tests/ui/support/stack.ts`, `startStack({ build }: { build: boolean })`, in this order:
1. `pnpm --filter @kodakiko/server build`.
2. If `build`: `pnpm --filter @kodakiko/client build:web` (Expo deletes and recreates `dist`, so this
   must happen **before** Caddy mounts it).
3. `docker compose -p kodakiko-ui -f infra/compose/docker-compose.ui.yml up -d --wait`.
4. Garage bootstrap (`tests/ui/support/garage.ts`): retry `garage status` every 1 s for up to 30 s,
   then the same Garage CLI steps as spec 05 §9 (F-S3-06) through `docker compose -p kodakiko-ui
   exec garage-ui /garage …`, bucket `kodakiko-ui`, key `ui`; returns the key id and secret.
5. Sets `process.env.UI_S3_ACCESS_KEY_ID` / `UI_S3_SECRET_ACCESS_KEY` (Playwright workers and the
   Android runner's seeds inherit them); every server process gets them as `S3_ACCESS_KEY_ID` /
   `S3_SECRET_ACCESS_KEY` in its environment (never written to a file).
6. `cli.js apply-bucket-cors` (reads `PUBLIC_BASE_URL`).
7. Spawn the API (it migrates the empty database at boot, spec 03); wait for `GET
   http://localhost:9580/health/ready` = 200 (timeout `UI_STACK_READY_MS = 120_000`).

`stopStack()` kills the API and runs `down -v`. Server output goes to `tests/ui/reports/server.log`.

## 4. Package `tests/ui` (`@kodakiko/ui-tests`, private)

```
tests/ui/
  package.json            scripts below
  support/                stack.ts, garage.ts, seed.ts, fixtures.ts, constants.ts, usernames.ts, *.test.ts
  scripts/                build-android-runner.ts
  web/                    playwright.config.ts, global-setup.ts, global-teardown.ts, specs/U-NN-<slug>.spec.ts
  android/                run.ts, install-maestro.sh, create-avd.sh, maestro.sha256,
                          flows/U-NN.flow.ts, flows/U-NN-<slug>[-pK].yaml, flows/common/*.yaml
  reports/                (gitignored)
```

| Script | Command |
|---|---|
| `test` | `vitest run support` (harness unit tests; part of `pnpm verify`) |
| `typecheck` | `tsc --noEmit` |
| `test:web` | `playwright test -c web/playwright.config.ts` |
| `test:android` | `node scripts/build-android-runner.ts && node android/dist/run.mjs` |
| `install:maestro` | `sh android/install-maestro.sh` |
| `fixtures` | `sh ../fixtures/media/generate.sh` |

`scripts/build-android-runner.ts` bundles `android/run.ts` and everything it imports (seeds use
`@kodakiko/client/testing`, whose sources use extensionless imports that plain Node cannot load)
with esbuild (`platform: 'node'`, `format: 'esm'`, `bundle: true`, `packages: 'bundle'`, externals
`libsodium-wrappers-sumo`) into `android/dist/run.mjs` (gitignored). Playwright needs no bundling:
its loader resolves extensionless imports.

Dependencies (`catalog:`): `@playwright/test`, `vitest`, `esbuild`, `typescript`, `fflate` (U-20
unzips the export), `libsodium-wrappers-sumo`; workspace `@kodakiko/client` (`./testing`),
`@kodakiko/core`. Root scripts `ui:web` and `ui:android` forward to `test:web` / `test:android`. The
UI runs themselves are not part of `pnpm verify` (they need Docker and, for Android, an emulator).

### 4.1 Seeding (`support/seed.ts`)

Accounts and content are created through the **real API** by Node client contexts (real crypto, real
Argon2id with the normal start parameters, because the app refuses weaker ones), never by SQL:

| Function | Built by spec | Signature and behavior |
|---|---|---|
| `seedInvite` | 07c | `(role: 'member' \| 'admin') => Promise<{ url: string; token: string }>`: runs `node --env-file=apps/server/.env.ui apps/server/dist/cli.js create-invite --role <role> --expires-days 1` with `S3_ACCESS_KEY_ID`/`S3_SECRET_ACCESS_KEY` from `UI_S3_*`; parses the printed URL |
| `seedAccount` | 07b | `(options: { username: string; password?: string; role?: 'member' \| 'admin' }) => Promise<SeededAccount>`; `SeededAccount = { username; password; recoveryWords: readonly string[]; sessionToken: string }`; `seedInvite(role ?? 'member')` + sign-up through `createTestAuthContext` (07b) with `baseUrl` `http://localhost:9580` |
| `seedDeletionRequest` | 07b | `(account: SeededAccount) => Promise<void>`: `POST /api/v1/users/me/delete` with `Authorization: Bearer <sessionToken>` |
| `seedEntries` | 10 | `(account: SeededAccount, entries: readonly SeedEntry[]) => Promise<readonly { entryId: string }[]>`; `SeedEntry = { takenAtUtc: string; offsetMinutes: number; note: string; tags: readonly string[]; media: readonly FixtureName[] }`; `createTestClientContext` (08) signed in as the account → entries service `createFromTray` with the fixture bytes → `syncNow` until the outbox is empty |
| `seedShare` | 17 | `(owner: SeededAccount, recipient: SeededAccount, entryIds: readonly string[], name: string) => Promise<{ collectionId: string }>`: sharing service as the owner: create the shared journal, add the entries, share with the recipient |

Usernames (`support/usernames.ts`, unit-tested): `ui-<flow id lowercased without the dash>-<project>-<n>`,
e.g. `ui-u02-desktop-0`, `ui-u02-mobile-0`, `ui-u05-android-0` (lowercase, digits and dashes only,
valid under 07b's username rule; the project part keeps the two Playwright projects apart).

Passwords: `UI_TEST_PASSWORD = 'correct horse battery staple'` unless the flow sets one. Seeds run
once per test file and project (Playwright `test.beforeAll`) or per flow (Maestro sidecar), never
share accounts between files.

### 4.2 Web runner (`web/playwright.config.ts`)

`testDir: './specs'`, `globalSetup` = `global-setup.ts` (`startStack({ build:
process.env.UI_SKIP_BUILD !== '1' })`), `globalTeardown` = `stopStack`, `workers: 1`,
`timeout: 120_000`, `expect.timeout: 30_000` (Argon2id runs in the page), `retries: 0`, reporter
`list` + `html` into `reports/web`. `use: { baseURL: 'http://localhost:9580', trace:
'retain-on-failure', video: 'retain-on-failure', serviceWorkers: 'allow' }`. Projects: `desktop`
(`devices['Desktop Chrome']`) and `mobile` (`devices['Pixel 7']`, touch). Every test also asserts,
in an `afterEach`, that the page logged no `pageerror` and no console message containing `Content
Security Policy` (collected by a fixture in `support/fixtures.ts`).

Helpers in `support/fixtures.ts`: `signUpThroughUi(page, invite)`, `signInThroughUi(page,
account)`, `unlockThroughUi(page, password)`, `readRecoveryWords(page)` (reads
`recoveryKey.word.1…24`), `confirmRecoveryWords(page, words)` (reads each
`recoveryKey.confirmLabel.n`, types the word at that position), `addFiles(page, names)`
(`setInputFiles` on `capture.fileInput`), `pasteFile(page, name)` and `dropFile(page, name)`
(dispatch `paste` / `drop` events with a `DataTransfer` built in the page from fixture bytes).

### 4.3 Android runner (`android/run.ts`)

`pnpm ui:android [U-NN…]`:
1. Checks: exactly one device in `adb devices` (else "Start the emulator: `emulator -avd
   kodakiko-ui`"), Maestro at the pinned path, Java ≥ 17.
2. `startStack({ build: false })`; `adb reverse tcp:9580 tcp:9580`, `tcp:9581 tcp:9581`,
   `tcp:8081 tcp:8081`.
3. Unless `UI_SKIP_BUILD=1`: `pnpm --filter @kodakiko/client prebuild:android`, then
   `./gradlew installDebug` in `apps/client/android`.
4. Metro: `pnpm --filter @kodakiko/client start:metro-ui` (07a script) with `CI=1` (non-interactive)
   and `EXPO_PUBLIC_UI_TEST=1` (07a `UI_TEST_MODE`: `LogBox.ignoreAllLogs(true)` so warning toasts do
   not cover buttons; nothing else changes); wait until `http://localhost:8081/status` answers
   `packager-status:running`.
5. Flows: files matching `^U-\d\d\.flow\.ts$` in `flows/` (sorted; `flows/common/` holds sub-flows
   only), filtered by the arguments. Each sidecar default-exports `{ seed?(project: 'android'):
   Promise<Record<string, string>>, parts: readonly { file: string; waitAfterMs?: number; adb?:
   readonly (readonly string[])[] }[] }`. The runner calls `seed`, then for each part runs `maestro
   test -e KEY=VALUE… flows/<file> --format junit --output reports/android/<file>.xml`, then each
   `adb` argument list (e.g. `['reverse', '--remove', 'tcp:9580']`), then waits `waitAfterMs`. A
   failing part fails the flow; the runner continues with the next flow and exits 1 at the end if
   any failed. Before the next flow it restores the three `adb reverse` rules.
6. Stop Metro, remove the `adb reverse` rules, `stopStack()`.

Android offline = removing the `adb reverse` rules for 9580 and 9581 (`adb shell cmd connectivity
airplane-mode …` does **not** cut traffic that runs over adb); it is the only offline method.

Sub-flows in `flows/common/`:
- `connect.yaml`: `extendedWaitUntil: { visible: { id: welcome.title }, timeout: 180000 }` (the
  first launch downloads the JS bundle from Metro) → `tapOn: { id: welcome.serverLabel }` →
  `inputText: ${SERVER_INPUT}` (the runner passes `http://localhost:9580` or the invite URL) →
  `tapOn: { id: welcome.continue }`.
- `sign-in.yaml`: `signIn.username`, `signIn.password` from `${USERNAME}` / `${PASSWORD}` →
  `signIn.submit` → `extendedWaitUntil` `home.signedInAs` (later the gallery) with `timeout: 60000`.
- `recovery-words.yaml` (U-01): `repeat: { times: 24 }` over: `scrollUntilVisible` `recoveryKey.word.${output.i}`
  → `copyTextFrom` → `evalScript: ${output.words.push(maestro.copiedText); output.i = output.i + 1}`
  (initialised by `evalScript: ${output.words = []; output.i = 1}`); then for n = 0..2:
  `copyTextFrom: { id: recoveryKey.confirmLabel.n }` → `evalScript:
  ${output.pos = parseInt(maestro.copiedText.replace(/\D/g, ''), 10)}` → `tapOn: { id:
  recoveryKey.confirmField.n }` → `inputText: ${output.words[output.pos - 1]}`.

Every flow's first part starts with `launchApp: { appId: com.x42697262.kodakiko, clearState: true }`
then `runFlow: common/connect.yaml`. A part that must resume a backgrounded app uses `launchApp: {
appId: com.x42697262.kodakiko, stopApp: false }` (the default stops the app first). Waits that
cover Argon2id use `extendedWaitUntil` with `timeout: 60000`.

## 5. Changes to earlier specs (already applied there)

- 01: `.gitignore` keeps `.env.ui` and `.env.*.example`; the catalog includes `@playwright/test`.
- 03: `PUBLIC_BASE_URL` may be `http` when `REQUIRE_HTTPS=false`; setting `RATE_LIMITS_ENABLED`
  (D-75).
- 07a: placeholder titles carry their text key as `testID`; keys `home.signedInAs`, `home.signOut`,
  `invite.title`; `src/config/zod-config.ts` (jitless zod, so the production CSP reports nothing);
  `src/config/build-flags.ts`; `sodiumReady` marker on web; debug-only cleartext to `localhost`,
  `127.0.0.1`, `10.0.2.2`; `allowLocalHttp` in the server URL store; required `testID` on
  primitives; script `start:metro-ui`.
- 07b: `createTestAuthContext` in `apps/client/src/testing.ts`; the text keys of §2; writes U-01 to
  U-05 first.
- 09: `add-exif.ts`, `check-fixtures.ts`; writes U-08 first. 13: zoom labels. 21 §2.2: the snippet's
  S3 origin is `{$S3_PUBLIC_ORIGIN}` and the file is created here.

When this spec is built, Sonnet **verifies** that the 07a items above exist (07a is built earlier)
and adds any that are missing.

## 6. Media fixtures (`tests/fixtures/media/`)

Committed binary files, generated once by `tests/fixtures/media/generate.sh` (ffmpeg ≥ 8.0). The
script writes each file's SHA-256 into `tests/fixtures/media/SHA256SUMS` (committed); a unit test
checks the files against it. `photo-gps.jpg` needs an EXIF block, which only spec 09's code can
write: spec 09 adds `tests/fixtures/media/add-exif.ts` (Node, imports the synthetic builders of
`packages/core/test/media/builders.ts`, spec 09 §7) that turns `photo-gps-base.jpg` into
`photo-gps.jpg`, and `check-fixtures.ts` (asserts with `readImageMetadata` / `readVideoMetadata`
that `photo-gps.jpg` has the date and GPS and `video-gps.mov` the location and time); `add-exif.ts`
appends the `photo-gps.jpg` line to `SHA256SUMS`. No flow before spec 13 uses `photo-gps.jpg`.

| File | Content | Generation |
|---|---|---|
| `photo-gps-base.jpg` → `photo-gps.jpg` | 2000×1500 test pattern; EXIF `DateTimeOriginal 2026:05:01 10:00:00`, `OffsetTimeOriginal +02:00`, GPS 48.8584 N 2.2945 E | `ffmpeg -f lavfi -i testsrc2=size=2000x1500 -frames:v 1 -q:v 3`; EXIF by spec 09's `add-exif.ts` |
| `photo-portrait.jpg` | 1200×1600, no EXIF | `testsrc2=size=1200x1600`, 1 frame |
| `screenshot.png` | 800×600 | `testsrc2=size=800x600`, 1 frame, PNG |
| `video-20s.mp4` | H.264 + AAC, 1280×720, 20 s, ~2.5 Mbit/s, faststart | `testsrc2=size=1280x720:rate=30` + `sine=frequency=440`, `-t 20 -c:v libx264 -pix_fmt yuv420p -b:v 2500k -c:a aac -movflags +faststart` |
| `video-gps.mov` | H.264, 640×480, 3 s, `©xyz` = `+48.8584+002.2945/`, creation time 2026-05-01T08:00:00Z | `-f mov -metadata location=… -metadata creation_time=…` (the MP4 muxer does not write `©xyz`, F-TOOL-16) |
| `video-short.webm` | VP9, 320×240, 2 s | `-c:v libvpx-vp9 -b:v 300k` |

Budget: all files together ≤ 10 MiB.

## 7. Smoke flows (this spec)

- `U-00` web (`specs/U-00-smoke.spec.ts`, both projects): open `/` → redirected to `/welcome`;
  `welcome.title` visible; `document.documentElement.dataset.sodiumReady === 'true'` within 30 s
  (the libsodium WebAssembly module loaded under the production CSP); `fetch('/api/v1/config')`
  from the page = 200; no CSP violation, no page error (the §4.2 guard).
- `U-00` Android (`flows/U-00.flow.ts`, one part `U-00-smoke.yaml`): `launchApp` with clear state →
  `extendedWaitUntil: { visible: { id: welcome.title }, timeout: 180000 }`. The first flow that
  connects to the server is U-01 (07b builds the server field).

## 8. Flow catalog

Each flow is written by the spec in the last column **before** that spec's screens (red) and must
pass when that spec is done (green). The spec in the last column is always the **last** spec the
flow needs. `W` = web in both projects, `W-d` = web `desktop` project only, `A` = Android. Behavior
rows in `behaviors.md` point here.

| Flow | Platform | Behavior | Steps (ids) | Spec |
|---|---|---|---|---|
| U-01 | W, A | B-001 | `seedInvite` → web: `/invite#<token>` (Android: connect with the invite URL) → `signUp.username`, `signUp.password`, `signUp.confirm` → `signUp.submit` → wait `recoveryKey.word.1` (60 s) → read the 24 words → `recoveryKey.done` → `confirmRecoveryWords` (Android: `common/recovery-words.yaml`) → `recoveryKey.confirmSubmit` → `home.signedInAs` contains the username | 07b |
| U-02 | W, A | B-004 | `seedAccount` → `/sign-in`: wrong password → `errors.invalidCredentials`; unknown username → `errors.invalidCredentials` with the same text; right password → `home.signedInAs` | 07b |
| U-03 | W, A | B-006 | `seedAccount` → `signIn.forgot` → `recover.username`, `recover.words` (the words joined by spaces), `recover.newPassword`, `recover.confirm` → `recover.submit` → `home.signedInAs` → `home.signOut` → sign in with the new password works; with the old one → `errors.invalidCredentials` | 07b |
| U-04 | W | B-009 (web) | `seedAccount` → sign in → `page.reload()` → `lock.title` → `lock.password` wrong → `lock.wrongPassword`; right → `home.signedInAs` | 07b |
| U-05 | W, A | B-010 | `seedAccount` + `seedDeletionRequest` → sign in → `cancelDeletion.title` → `cancelDeletion.submit` → `home.signedInAs` | 07b |
| U-06 | W | B-020, B-022 | `seedAccount` → sign in → `gallery.capture` → `addFiles(photo-gps.jpg, video-20s.mp4)` → `capture.trayItem.1` visible → Next → note "Picnic", tag "family" → Save → `gallery.tile.0` → open → `viewer.pageIndicator` "1 / 2"; the location row is shown | 13 |
| U-07 | W | B-022 offline | as U-06 up to Save, but `context.setOffline(true)` before Save → `gallery.tilePending.0` → `context.setOffline(false)` → the dot disappears within 60 s → a second context signed in to the same account shows `gallery.tile.0` | 13 |
| U-08 | W | B-028 | `seedAccount` → sign in → `/capture` → `pasteFile(screenshot.png)` → `capture.trayItem.0`; `dropFile(photo-portrait.jpg)` on `capture.dropZone` → `capture.trayItem.1` | 09 |
| U-09 | A | B-020 | sign in → `gallery.capture` → tap `capture.shutter` → `capture.trayItem.0`; `capture.mode.video`, tap `capture.shutter`, wait, tap again (records a few seconds; spec 22 §11.3 replaced hold-to-record) → `capture.trayItem.1`; Next → Save → `gallery.tile.0`. Uses the emulator's virtual camera (UNVERIFIED for vision-camera: if the camera does not start, mark U-09 `manual` in `behaviors.md` and report) | 11 |
| U-10 | W, A | B-040, B-041 | `seedEntries`: 3 entries on 3 different past days, tags `a` / `a b` / `c`, one note contains "lake" → 3 day headers → Filter → tags `a`, `b` → Apply → exactly `gallery.tile.0` → Clear All → Search "lake" → exactly 1 tile | 11 |
| U-11 | W, A | B-043 | `seedEntries` on the 3rd and the 17th of the current month → Calendar tab → `calendar.day.<YYYY-MM-17>` → `/day/…` shows `gallery.tile.0` | 12 |
| U-12 | W-d, A | B-044 | `seedEntries` 1 entry with `photo-gps.jpg`, `photo-portrait.jpg` → open → `viewer.pageIndicator` "1 / 2". Android: `doubleTapOn: { id: viewer.zoomable.0 }` → label "Photo 1, zoomed" → `swipe` left on it → indicator still "1 / 2" → `doubleTapOn` → label "Photo 1" → `swipe` left → "2 / 2". Web desktop: `dblclick` on `viewer.zoomable.0` → `aria-label` "Photo 1, zoomed" → `dblclick` → "Photo 1"; Ctrl + `mouse.wheel(0, -300)` → zoomed; Escape key not involved; `ArrowRight` after un-zooming → "2 / 2". Pinch is covered by the zoom-math unit tests and by hand || 14 |
| U-13 | W-d | B-046 | `seedEntries` with `video-20s.mp4` → `context.route` delays bucket responses 250 ms (Chrome would otherwise buffer the whole fixture on localhost before the seek) → open → play → `video.player` → `page.evaluate` sets the `<video>` `currentTime = 15` → within 10 s `currentTime ≥ 15` and not paused; the bytes of responses from `localhost:9581` (counted with `context.on('response')`, which also sees service-worker requests in Chromium) are less than the object's ciphertext size | 15 |
| U-14 | W-d | B-008 (web) | `page.clock.install()` **before** the first `goto` → `seedAccount`, sign in → `page.clock.fastForward('16:00')` with no input → `lock.title` | 16 |
| U-15 | A | B-008 (Android) | part 1: sign in → Settings › Security → auto-lock 1 minute → `pressKey: Home`; `waitAfterMs: 70000`; part 2: `launchApp: { appId: com.x42697262.kodakiko, stopApp: false }` → `lock.title` | 20 |
| U-16 | W-d | B-105, B-008 | `chromium.launchPersistentContext(tempDir, { baseURL: 'http://localhost:9580' })` with `page.clock.install()` → sign in → Settings › Security keep unlocked on → fast-forward 16 minutes → still `gallery` (no idle lock, D-63) → close the context → relaunch with the same directory → the gallery without a password → "Lock Now" → `lock.title` | 20 |
| U-17 | W-d | B-080, B-082 | `seedAccount` A and B, `seedEntries` 2 for A, `seedShare` → context B: Shared tab → the journal with 2 tiles, no "Edit" in an entry; contexts A and B open the security code screens (B through Settings › Account, never `page.goto`, which reloads and locks the web app) → the texts are equal; B "Leave" → the journal disappears | 17 |
| U-18 | W-d | B-083, B-086 | `seedEntries` 1 entry with `photo-gps.jpg`, `video-gps.mov`, `video-20s.mp4` and a note → create a link: untick `video-gps.mov`, notes off → read the URL → a fresh context with no account opens it → exactly `linkViewer.item.0` and `.1`; no note text; the photo "Download" (`page.waitForEvent('download')`) yields a JPEG whose `readImageMetadata` (Node) has no date and no GPS; the video plays and seeks to 10 s | 18 |
| U-19 | W-d | B-100 | `seedAccount({ role: 'admin' })` → Settings › Admin → New Invite → URL shown → a second context signs up with it → People shows the new user → set quota 1 GB → the row shows it | 20 (the Settings row that opens Admin is spec 20's) |
| U-20 | W-d | B-102 | `page.addInitScript` deletes `window.showDirectoryPicker` (forces the download fallback, spec 20 §7.3) → `seedEntries` 2 entries → Settings › Export → collect every `download` → unzip in Node with `fflate` → one `entry.md` per entry; media bytes equal the fixture files | 20 |
| U-21 | A | B-104 | sign in → Settings › Account → Change Server… → confirm → `welcome.serverLabel` visible and empty | 20 |
| U-22 | A | — | `launchApp clearState` → `welcome.local` → local setup (username, password, `localSetup.understand`) → recovery key confirm → capture a photo with the emulator camera → `gallery.tile.0` visible and no `gallery.tilePending.0` | 20a |
| U-23 | A | — | part 2 of U-22's device state (no `clearState`): sidecar seeds an invite → Settings › Account › Connect a Server… (`10.0.2.2` UI stack, invite link, password) → `connect.done`; sidecar checks the server has 1 entry for the username | 20a |
| U-24 | W-d | — | `seedEntries` a photo and a video → Filter sheet: `sheet.filter.video` include → Display tab: display mode List → 1 tile with the clip's note; lock and unlock → still 1 tile (settings persist) | 22 |
| U-25 | W | — | `seedEntries` 3 entries tagged beach, city, city → tag tabs All, beach, city; `gallery.tagTab.1` → 2 tiles; All → 3 | 22 |

Behaviors that stay `manual` (neither tool can drive them here; recorded in `behaviors.md`):
fingerprint unlock (B-009 Android), the background upload notification and resume (B-060),
Android decrypted-video cleanup (B-047), pinch gestures (part of B-044), Wi-Fi-only uploads
(B-061). A `ui` flow counts for the spec in the last column only; other specs named in the same
`behaviors.md` row are done when their own layers pass (README rule 8).

## 9. Tests of the harness itself

Unit (vitest, `tests/ui/support/*.test.ts`, run by `pnpm verify`): `listTestId`; the username
generator (`U-02` + `desktop` + 0 → `ui-u02-desktop-0`; output always passes the core username
check); sidecar discovery (`U-03.flow.ts` found, `common/x.yaml` and `U-3.flow.ts` ignored) and
part loading with `waitAfterMs` and `adb`; `SHA256SUMS` matches the fixture files. The smoke flows
(§7) prove the stack.

## 10. Steps

1. Tools: `install-maestro.sh`, `create-avd.sh`, Playwright install; fixtures (`generate.sh`).
2. `docker-compose.ui.yml`, `ui/Caddyfile`, `ui/garage.toml`, `.env.ui`, the security snippet.
3. Verify the 07a, 01 and 03 items of §5 exist (built earlier); add any that are missing.
4. `stack.ts`, `garage.ts`, `seed.ts` (`seedInvite`), `usernames.ts`, the web config and fixtures,
   the Android runner, its bundler script and `common/*.yaml`.
5. Smoke flows. Spec 07b starts by writing U-01 to U-05 (red).

## 10a. Implementation answers (2026-11-12)

1. `signUpThroughUi(page, invite, credentials)` (third parameter `{ username, password }`).
2. `unlockThroughUi` fills `lock.password` and presses Enter (no submit id).
3. `tests/ui` depends on the `@kodakiko/client` workspace package now; spec 07b adds `src/testing.ts`
   and its `./testing` export. Until then `test-ids.test.ts` imports the client file by relative path.
4. esbuild bundles every sidecar (`flows/U-NN.flow.ts`) and its seeds into `run.mjs` (dynamic import
   glob); intended.
5. Facts: `caddy:2` = v2.11.9 @ `sha256:9c1b76fb701c1f77383d3124d9f626e38bcf83bc1255ed47475d4f2420701d03`;
   Playwright 1.64.1 installs Chrome Headless Shell 156.0.8078.4 (`chromium_headless_shell-1248`);
   Maestro 2.12.1 unpacks to `~/.maestro-kodakiko/2.12.1/maestro/bin/maestro`.

## 10b. Android emulator networking (D-81, 2026-11-12)

Measured on the `akon` emulator (API 34, x86_64): `adb reverse` forwards nothing (device-side
`127.0.0.1:<port>` gets no answer, also to Metro and after an adb server restart), while the emulator's
host alias `10.0.2.2` reaches host services bound to `127.0.0.1` (F-AND-02). The Android flows therefore
use `10.0.2.2`, not `adb reverse`:

1. `infra/compose/ui/Caddyfile`: the site address becomes `http://:9580` (any Host header; the
   emulator sends `10.0.2.2:9580`).
2. Client: `LOCAL_HTTP_HOSTS` gains `10.0.2.2` (still only when `allowLocalHttp`, i.e. `__DEV__`;
   the debug network config already allows cleartext to it).
3. Android runner: starts the API with environment overrides `PUBLIC_BASE_URL=http://10.0.2.2:9580`
   and `S3_PUBLIC_ENDPOINT=http://10.0.2.2:9581` (invite URLs and presigned URLs must be reachable from
   the emulator; `REQUIRE_HTTPS=false` already allows http); `startStack` takes optional
   `apiEnvOverrides`. `apply-bucket-cors` keeps using `.env.ui` (CORS is irrelevant for native).
   The web runner is unchanged (`localhost`).
4. `flows/common/connect.yaml`: `SERVER_INPUT` is `http://10.0.2.2:9580` (or the invite URL, which
   now starts with that origin). The runner no longer sets `adb reverse` for 9580/9581; Metro is
   reached by the debug build's default `10.0.2.2:8081`.
5. Android "offline" (§4.3) becomes: stop the UI stack's Caddy container
   (`docker compose -p kodakiko-ui stop caddy-ui`) and start it again afterwards. Sidecar parts get an
   optional field `caddy?: 'stop' | 'start'`, executed by the runner after the part's `adb` commands
   (validated by `parseSidecar`; unit-tested).
7. `RATE_LIMITS_ENABLED=false` also accepts the `PUBLIC_BASE_URL` host `10.0.2.2` (spec 03 settings row).
6. Run the Android runner with the system `adb` (outside `nix develop`): mixing the Nix `adb` with
   the system one restarts the adb server.

Physical devices still need `adb reverse` (or a LAN address); that path is documented in
`docs/development.md` and not used by the automated flows.

## 10c. Flow mechanics on Android (2026-11-12)

- Fields are filled with `tapOn` + `inputText` (selection replaces the value, 07b §5b); no `eraseText`.
- Before tapping a field that may sit under the keyboard, the flow runs `hideKeyboard` and, if
  needed, `scrollUntilVisible` on the field's id.
- U-05 on Android: after `cancelDeletion.submit`, if `lock.title` is visible (device without a
  screen lock, 07b §5b item 3) the flow unlocks with the password (`lock.unlockPassword` →
  `lock.password` → Enter) and then expects `home.signedInAs`. The emulator keeps no screen lock.
- U-05 waits for **either** screen first: `extendedWaitUntil` visible id matching the regex
  `(lock\.title|home\.signedInAs)`, then `runFlow` with `when: visible: lock.title`, so a slow
  network cannot skip the unlock branch.
- Accepted: the runner's `am force-stop` + `APP_SETTLE_MS` before each flow (no unit test; emulator
  only); `src/composition/route-by-state.ts` shared by `app/index.tsx` and the tabs layout.

## 11. Verification checklist

- [ ] `pnpm ui:web` runs `U-00` green in both projects; the HTML report opens.
- [ ] `pnpm ui:android U-00` green on the `kodakiko-ui` emulator.
- [ ] `pnpm verify` runs the harness unit tests (§9).
- [ ] A deliberate CSP break (temporarily remove `'wasm-unsafe-eval'` from the snippet, then restore)
      makes `U-00` web fail with the CSP message — proves the guard works.
- [ ] `docker compose -p kodakiko-ui ps` is empty after a run (teardown works).
- [ ] Fixtures ≤ 10 MiB total and `SHA256SUMS` matches.
