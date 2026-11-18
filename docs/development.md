# Development

## Toolchain

- **Android app (`apps/android`):** JDK 21 runs Gradle (the Metro compiler plugin needs it) and the
  JVM tests; the code compiles to Java 17 bytecode and Gradle never downloads a JDK. The Android
  SDK (platform 37, build-tools 37.0.0 and 36.0.0) comes from `nix develop` (`flake.nix`), which
  also provides scrcpy and resvg. The flake still exports a JDK 17 `JAVA_HOME`; point `JAVA_HOME`
  at a JDK 21 before running Gradle. Robolectric tests need the host libsodium library
  (`libsodium23` on Debian/Ubuntu, F-KT-24). Use the wrapper (`./gradlew`, Gradle 9.9.0).
- **TypeScript parts (server, link viewer, core, contract, tests):** Node ≥ 22.18 and pnpm (any
  version ≥ 11 switches to the pinned 12.17.0 by itself).
- Docker with the Compose plugin for the dev, test and UI stacks.

Gradle builds are large; keep several GB free. `./gradlew clean` (in `apps/android`) removes the
build outputs. Do not run `./gradlew --stop`: it stops every Gradle daemon of the same version on
the machine, including other projects'.

## Local stack

1. `docker compose -p kodakiko-dev -f infra/compose/docker-compose.dev.yml up -d --wait`
2. `sh infra/compose/dev/bootstrap-garage.sh`: creates `apps/server/.env.dev` from the example if
   it is missing and writes the S3 keys into it without printing them. Safe to run again.
3. `pnpm --filter @kodakiko/server build`, then
   `node --env-file=apps/server/.env.dev apps/server/dist/cli.js apply-bucket-cors`.
4. `pnpm --filter @kodakiko/server dev`.
5. First account: `node --env-file=apps/server/.env.dev apps/server/dist/cli.js create-invite --role admin --expires-days 7`,
   then open the app, connect to the server and paste the invite URL.

The dev API listens on `0.0.0.0:8080` so the Caddy container can reach it; block port 8080 from
the LAN with the host firewall. Caddy's dev ports are bound to `127.0.0.1`. `https://localhost:9443/`
shows the "Get the Android app" page; public links open at `https://localhost:9443/s/…` in the link
viewer.

## Android builds

| Command (in `apps/android`) | What it does |
|---|---|
| `./gradlew :app:assembleDebug` | Debug APK, app id `com.x42697262.kodakiko.next`; debug log on by default |
| `./gradlew :app:installDebug` | Builds and installs the debug APK on the connected device or emulator |
| `./gradlew :app:assemblePreview` | Preview APK (`app/build/outputs/apk/preview/app-preview.apk`): a release build with the `.next` id and the label "Kodakiko Preview", signed with the local debug key, talking only to HTTPS servers with system certificates; debug log on by default |
| `./gradlew :app:assembleRelease` | Release APK; unsigned locally, signed only in CI |
| `./gradlew verify` | Every check (see [testing.md](testing.md)) |

The `.next` builds install next to the released app (Kotlin or v0.3.1), so they never touch the
real journal. Android Keystore keys belong to one app id, so a `.next` build cannot read the real
app's keys (F-KT-14).

Against the dev stack with a debug build: copy Caddy's root certificate
(`docker cp kodakiko-dev-caddy-dev-1:/data/caddy/pki/authorities/local/root.crt .`), install it as
a user CA on the device (debug builds trust user CAs; preview and release builds do not),
`adb reverse tcp:9443 tcp:9443` and `adb reverse tcp:9444 tcp:9444`, then enter
`https://localhost:9443` on the welcome screen. On the emulator the host is `10.0.2.2`.

## Releases

From v0.4.0 the released Android app is the Kotlin client in `apps/android`. `app-version-name` in
`apps/android/gradle/libs.versions.toml` sets its version name and version code
(`major * 10000 + minor * 100 + patch`, 0.4.1 → 401, above v0.3.1's 301); the application id stays
`com.x42697262.kodakiko`, so it installs as an update over v0.3.1. Pushing a tag `v*` runs
`.github/workflows/android-release.yml`: it checks that the tag equals `app-version-name`, runs
`pnpm verify` and `./gradlew verify`, builds `:app:assembleRelease`, signs it with the keystore from
the repository secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`), refuses a debug-signed APK and publishes it with a `.sha256` as a GitHub
release. Pushes to `main` that touch `apps/android` build it unsigned without publishing, to warm the
Gradle cache. `kotlin.yml` runs `./gradlew verify` on pull requests that touch the Android app and on pushes to
`main` and `feat/**`; `ts.yml` runs `pnpm verify` on the same events (pull requests that change more than docs).

Release checklist: bump `app-version-name`, write the PR description and release notes
(`docs/planning/pr/`, local), let CI pass, then tag.

## Working on the Android app

- Layers and module map: [architecture.md](architecture.md). Shared logic in `:shared:*`
  (Kotlin Multiplatform `commonMain`, no Android or Java APIs), I/O in `:data:*`, device features in
  `:platform:*`, use cases in `:service:*`, screens in `:ui:feature:*`, wiring in `:app`. Feature
  modules never depend on each other; they navigate with `Route` keys from `:ui:core`.
- A screen is `XScreen` (gets its ViewModel through Metro, collects state, handles events and
  navigation) plus `XContent` (stateless). ViewModels expose `StateFlow<XState>`; state classes are
  `@Immutable`.
- No comments, except the two-line Mihon attribution header on adapted files. Numbers, durations
  and colors in `*Constants.kt`; UI text in `ui/core/src/main/res/values/` (`strings.xml` is
  generated from the v0.3.1 text by `tests/fixtures/ui-strings/emit-android.ts`; new text goes in
  `strings_v040.xml`, edit-page text of v0.4.1 in `strings_v041_edit.xml`); test ids in `TestTags`.
  `tooling-tests` rejects comments, magic numbers and literal UI text.
- New dependencies: only from `gradle/libs.versions.toml`, released at least 7 days earlier, listed
  in [dependencies.md](dependencies.md) (the About license list is generated from it:
  `node apps/android/tools/licenses/emit-licenses.ts`). Dependency verification and locking are on:
  after adding one, update `gradle/verification-metadata.xml` and the lockfiles.
- No I/O on the main thread (disk, database, crypto and network run on IO dispatchers); secrets are
  `ByteArray`s and are wiped after use.
- The debug log (Settings › Advanced › View debug log) helps on a phone: it records ids, counts,
  timings and error class names, never content. Debug and preview builds start with it on.

## Working on the TypeScript parts

- Server, link viewer, core and contract follow the same rules: no comments, no magic values
  (ESLint enforces both), strings and keys in their catalogs.
- New npm packages must be in the pnpm catalog (`pnpm-workspace.yaml`, `"catalog:"` in the
  package), released at least 7 days ago, and listed in [dependencies.md](dependencies.md).
- The link viewer (`apps/link-viewer`) builds with `node build.ts` (esbuild) into the Caddy image;
  its bundle budget and CSP are checked by its unit tests.

## Tests and screenshots

See [testing.md](testing.md). `docs/screenshots/` holds a curated copy of the Roborazzi baselines
([screenshots/README.md](screenshots/README.md)).

Engineering rules for every change: [engineering-guidelines.md](engineering-guidelines.md).
