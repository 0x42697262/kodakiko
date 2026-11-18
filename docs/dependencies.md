# Dependency allowlist (D-11)

Every package the repository uses, with its version and the reason it is here. A package that is
not listed may not be added, replaced or bumped; adding one is recorded here first (ADR 0010).

## Version rule

Every version below is the **newest release published at least 7 days before 2026-11-11**
(cutoff 2026-11-04), so it satisfies `minimumReleaseAge` (7 days) on the day it was chosen. Pin
exactly these versions. If an install happens much later, do **not** pick newer versions;
it reports "allowlist is N days old" and this file is refreshed by the same rule.

## Policy settings (go in `pnpm-workspace.yaml`, F-TOOL-01..04)

```yaml
minimumReleaseAge: 10080
minimumReleaseAgeStrict: true
saveExact: true
strictDepBuilds: true
allowBuilds:
  esbuild: true
catalogMode: strict
```

- `minimumReleaseAge` is in minutes: 10,080 = 7 days.
- `allowBuilds` starts with only `esbuild` (it downloads its native binary in a postinstall
  script). When `pnpm install` stops with `ERR_PNPM_IGNORED_BUILDS` for another package, the implementer
  adds it **only if that package is on this allowlist**, with `true` if its build is needed for the
  app to run (native binary download) and `false` otherwise, and records the line here. Anything
  else stops the work. Recorded so far: none (the Expo client's `react-native-libsodium: true` left
  with `apps/client`, K261).
- `packageManager` in the root `package.json`: `pnpm@12.17.0` (2026-10-30, compliant).

## Allowlist

### Crypto

| Package | Version | Used by | Purpose | Audit status |
|---|---|---|---|---|
| `libsodium-wrappers-sumo` | 0.8.4 | core tests, UI-test seeding (`tests/ui`), e2e launcher (`tests/e2e`), link viewer (`apps/link-viewer`: page and media service worker; no Argon2id there) | libsodium WASM; sumo for Argon2id (F-LIB-03) | libsodium audited (F-LIB-04) |

The server uses **no** crypto package (`node:crypto` only, architecture.md §4.1).

### Shared tooling (root devDependencies unless stated)

| Package | Version | Purpose |
|---|---|---|
| `typescript` | 6.0.3 | type checking. **Not 7.x**: `typescript-eslint` 8.72 requires `<6.1.0` (F-TOOL-11) |
| `vitest` | 5.1.0 | tests (needs Node ^22.12 or ≥24) |
| `eslint` | 10.14.0 | lint |
| `typescript-eslint` | 8.72.1 | TypeScript lint rules |
| `prettier` | 3.9.10 | formatting |
| `esbuild` | 0.28.2 | server bundle (architecture.md §4.1), service worker bundle (storage.md §5), link viewer bundles (`apps/link-viewer/build.ts`: page script, media service worker, stylesheet) |
| `@types/node` | 22.20.5 | server and scripts |
| `zod` | 4.6.7 | runtime dependency of contract, core, server, link viewer (jitless under the CSP) |

### Server

| Package | Version | Purpose |
|---|---|---|
| `fastify` | 5.12.8 | HTTP framework (F-TOOL-07); its bundled `pino` logger is used, no direct `pino` dependency |
| `fastify-type-provider-zod` | 7.0.0 | contract-bound validation and serialization (needs Zod ≥ 4.2, F-TOOL-07) |
| `@fastify/rate-limit` | 11.2.2 | api.md §1.1 |
| `@fastify/helmet` | 13.2.0 | security.md §3 |
| `kysely` | 0.29.8 | typed SQL + Migrator (F-TOOL-08) |
| `pg` | 8.23.1 | Postgres driver |
| `@types/pg` | 8.23.1 | dev |
| `@aws-sdk/client-s3` | 3.1161.0 | bucket adapter (F-TOOL-10) |
| `@aws-sdk/s3-request-presigner` | 3.1161.0 | presigned URLs |

`@fastify/cors` was removed in review 1: the link viewer calls the API on its own origin
(deployment.md §2), and the Android app is not subject to CORS.

### Removed client packages

The Expo client's packages (the Expo SDK 58 family, React Native, `react-native-libsodium`,
`@shopify/flash-list`, `idb`, `marked`, `fflate`, `@types/react`, …) were removed with `apps/client`
in v0.4.1 (K261, D-167); the `v0.3.1` tag keeps their rows. The Android app's libraries are in the
Kotlin section below.

### Test tooling (UI tests, D-74)

| Package / tool | Version | Why |
|---|---|---|
| `@playwright/test` | 1.64.1 (2026-10-14) | link viewer flow (U-18); devDependency of `tests/ui` only; its Chromium build is downloaded by `playwright install chromium` (tied to this version) |
| Maestro CLI | 2.12.1 (2026-11-02) | Android UI flows; not an npm package; downloaded by `tests/ui/android/install-maestro.sh` and checked against SHA-256 `54dbb0716425626604973cbb374c585ca30272375d9ce15177a1152765ddd9d7`; Apache-2.0 |
| ffmpeg | ≥ 8.0 (developer machine) | generates the committed media fixtures once (`tests/fixtures/media/generate.sh`); never installed by the project, never in an image |

`tests/ui` also uses `esbuild` and `libsodium-wrappers-sumo` (already allowlisted).

### Container images

Pinned by digest when first pulled; the digest is recorded here in the same commit.

| Image | Tag | Digest | Purpose |
|---|---|---|---|
| `postgres` | `17` @ `sha256:ff87138949ad9a46f32ff23bd6852b52ce024a13dffe9b4087be98292cdd3b4f` (pulled 2026-11-12) | recorded | database (`deployment.md` §2), also the test database |
| `dxflrs/garage` | `v2.5.0` @ `sha256:677e5b758e4425a325b811c425c026bf35a4bf42f7ddbaa773de3f4fd779a5a5` (linux/amd64, pulled 2026-11-12) | recorded | object storage (F-S3-02) |
| `caddy` | runtime `caddy:2` = v2.11.9 @ `sha256:9c1b76fb701c1f77383d3124d9f626e38bcf83bc1255ed47475d4f2420701d03` (pulled 2026-11-12, used by the UI stack and the production image) | recorded | TLS + reverse proxy, built with the DNS provider plugin (deployment) |
| `node` | `22.23.4-bookworm-slim` @ `sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d` (tag pushed 2026-11-05, recorded 2026-11-13; the floating `22-bookworm-slim` tag was 2 days old, under the 7-day rule) | recorded | API runtime, build stages, `garage-setup` (deployment) |
| `caddy` | `2.11.8-builder` @ `sha256:d4eb070c36154bd2d4c94b40359a03c6168346d5af5404963c74691ab77fb63f` (tag pushed 2026-11-05) | recorded | xcaddy build with `CADDY_VERSION=v2.11.8` and `DNS_MODULE=github.com/caddy-dns/cloudflare@v0.2.4` (deployment, D-68); the production runtime stage reuses the recorded `caddy:2` digest above |
| `rclone/rclone` | `1.75.2` @ `sha256:2687085f718d3c628f7fdfb77c52a1d344332aed543110bb88088f2c70d43eb5` (tag pushed 2026-10-09; 1.76.0 was 7 days old) | recorded | backups: dump copy and bucket sync (deployment, operations guide §5) |

### CI actions

Pinned by commit SHA (release workflow); each release was older than 7 days when pinned (2026-11-13).

| Action | Version | Commit | Used by |
|---|---|---|---|
| `actions/checkout` | v7.0.1 (2026-07-20) | `3d3c42e5aac5ba805825da76410c181273ba90b1` | `android-release.yml`, `kotlin.yml`, `ts.yml` |
| `actions/setup-node` | v7.1.0 (2026-10-08) | `949feb2413d6458794dcd2491c4babbbce0c15c1` | `android-release.yml`, `kotlin.yml`, `ts.yml` |
| `actions/setup-java` | v6.2.0 (2026-11-04) | `77dd5b2ce225ea11469928ac317850245a74ae78` | `android-release.yml`, `kotlin.yml` |
| `gradle/actions/setup-gradle` | v6.4.1 (2026-10-28; pinned 2026-11-14) | `8da46b539cd8fe6ab27ca75128919a18d757604c` | `android-release.yml`, `kotlin.yml` (Gradle dependency and build cache; written on `main`, read by tag and pull-request builds) |

### Deliberately excluded

| Package | Reason |
|---|---|
| `exifr` | stale since 2021, React Native undocumented (F-LIB-15); in-repo bounded EXIF reader instead (media.md §4.1) |
| `tus-js-client` | foreground-only, extra protocol; presigned multipart is enough (storage.md §3) |
| MinIO server image | unmaintained (F-S3-01); Garage instead |
| Any SRP library | ADR 3 |
| Any BIP-39 library | word list committed as data (crypto.md §5.6) |
| `typescript` 7.x | breaks `typescript-eslint` (F-TOOL-11) |

## Kotlin client allowlist (Android app, `apps/android`, D-106, ADR 11)

The Android app's libraries, chosen 2026-11-14 (task K002) from Maven Central, Google Maven and
the Gradle plugin portal metadata; release date = `Last-Modified` of the version's POM (F-KT-11).
The app's Settings › About › Open source licenses list is generated from the tables of this section
by `node apps/android/tools/licenses/emit-licenses.ts` (rows whose artifact ships in the release
build, D-156/D-160; `pnpm verify` runs it with `--check`).

### Kotlin version rule

- Every version is the **newest stable release published at least 7 days before 2026-11-14**
  (cutoff 2026-11-07). Newer releases seen on that day are listed under "too new" and are not used.
- Stable = no `alpha`, `beta`, `rc`, `dev`, `eap`, `M`, `SNAPSHOT`. Exceptions are named in the
  row with their reason.
- The catalog is `apps/android/gradle/libs.versions.toml`; a module may only use catalog entries.
  Versions of one family (Kotlin plugins, Compose via BOM, CameraX, Media3, SQLDelight, Metro,
  Voyager, Coil) move together.
- Repositories: `google()`, `mavenCentral()`, `gradlePluginPortal()` (plugins only). **No JitPack**
  and no other repository.
- Gradle dependency verification (`gradle/verification-metadata.xml`, sha256) and dependency
  locking are on (K012). The Gradle wrapper is checked by `gradle/actions/wrapper-validation`.
- JDK: the installed JDK 21 runs Gradle; bytecode target 17 (as Mihon). No toolchain download.
- Licenses allowed for shipped code: Apache-2.0, MIT, BSD-2/3-Clause, MPL-2.0 (file-level copyleft,
  unmodified use). EPL-2.0 is allowed for test-only artifacts (JUnit). JNA is dual
  Apache-2.0/LGPL-2.1; we use it under Apache-2.0.

### Catalog coordinates (machine-checked)

`CatalogAllowlistTest` (apps/android tooling tests) requires every library and plugin in
`apps/android/gradle/libs.versions.toml` to appear below with the same version (`bom` = version
from the Compose BOM, no version in the catalog). The tables after this block give dates, licenses
and reasons. Added 2026-11-14 for M1 (same 7-day rule): `junit:junit` 4.13.2 (2021-02-13, EPL-1.0,
test only: Robolectric and instrumented tests run on JUnit 4), `org.junit.platform:junit-platform-launcher`
6.1.3 (JUnit family, needed on the JVM test runtime by Gradle 9), the Kotlin Compose compiler and
Gradle plugin artifacts used by `build-logic` (Kotlin family), Compose UI artifacts (BOM).

```
plugin org.jetbrains.kotlin.multiplatform 2.4.21
plugin org.jetbrains.kotlin.jvm 2.4.21
plugin org.jetbrains.kotlin.plugin.compose 2.4.21
plugin org.jetbrains.kotlin.plugin.serialization 2.4.21
plugin com.android.application 9.5.1
plugin com.android.library 9.5.1
plugin com.android.kotlin.multiplatform.library 9.5.1
plugin com.android.test 9.5.1
plugin app.cash.sqldelight 2.4.1
plugin dev.zacsweers.metro 1.5.2
plugin com.diffplug.spotless 8.11.0
plugin androidx.baselineprofile 1.5.0
plugin com.mikepenz.aboutlibraries.plugin.android 15.3.0
plugin io.github.takahirom.roborazzi 1.77.0
lib org.jetbrains.kotlin:kotlin-gradle-plugin 2.4.21
lib org.jetbrains.kotlin:compose-compiler-gradle-plugin 2.4.21
lib org.jetbrains.kotlin:kotlin-serialization 2.4.21
lib com.android.tools.build:gradle 9.5.1
lib com.diffplug.spotless:spotless-plugin-gradle 8.11.0
lib com.pinterest.ktlint:ktlint-cli 1.8.0
lib org.jetbrains.kotlinx:kotlinx-coroutines-core 1.11.0
lib org.jetbrains.kotlinx:kotlinx-coroutines-android 1.11.0
lib org.jetbrains.kotlinx:kotlinx-coroutines-test 1.11.0
lib org.jetbrains.kotlinx:kotlinx-serialization-json 1.12.0
lib org.jetbrains.kotlinx:kotlinx-datetime 0.8.0
lib org.jetbrains.kotlinx:kotlinx-io-core 0.9.2
lib com.squareup.okio:okio 3.18.2
lib org.jetbrains:markdown 0.7.18
lib com.goterl:lazysodium-android 5.2.0
lib com.goterl:lazysodium-java 5.2.0
lib net.java.dev.jna:jna 5.19.1
lib androidx.compose:compose-bom 2026.09.00
lib androidx.compose.ui:ui bom
lib androidx.compose.ui:ui-tooling-preview bom
lib androidx.compose.ui:ui-tooling bom
lib androidx.compose.ui:ui-test-junit4 bom
lib androidx.compose.ui:ui-test-manifest bom
lib androidx.compose.foundation:foundation bom
lib androidx.compose.material3:material3 bom
lib androidx.compose.material3:material3-adaptive-navigation-suite bom
lib androidx.activity:activity-compose 1.13.0
lib androidx.lifecycle:lifecycle-viewmodel-compose 2.11.0
lib androidx.lifecycle:lifecycle-runtime-compose 2.11.0
lib androidx.lifecycle:lifecycle-process 2.11.0
lib androidx.core:core-ktx 1.19.1
lib androidx.core:core-splashscreen 1.2.0
lib androidx.biometric:biometric 1.1.0
lib androidx.documentfile:documentfile 1.1.0
lib androidx.profileinstaller:profileinstaller 1.4.1
lib androidx.media3:media3-exoplayer 1.11.1
lib androidx.media3:media3-ui-compose 1.11.1
lib androidx.media3:media3-ui 1.11.1
lib androidx.camera:camera-core 1.6.2
lib androidx.camera:camera-camera2 1.6.2
lib androidx.camera:camera-lifecycle 1.6.2
lib androidx.camera:camera-video 1.6.2
lib androidx.camera:camera-compose 1.6.2
lib cafe.adriel.voyager:voyager-navigator 2.2.21-1.10.3
lib cafe.adriel.voyager:voyager-tab-navigator 2.2.21-1.10.3
lib cafe.adriel.voyager:voyager-transitions 2.2.21-1.10.3
lib dev.zacsweers.metro:runtime 1.5.2
lib dev.zacsweers.metro:metrox-viewmodel-compose 1.5.2
lib com.squareup.okhttp3:okhttp 5.5.1
lib app.cash.sqldelight:android-driver 2.4.1
lib app.cash.sqldelight:coroutines-extensions 2.4.1
lib io.coil-kt.coil3:coil 3.6.3
lib io.coil-kt.coil3:coil-compose 3.6.3
lib me.saket.telephoto:zoomable-image-coil3 0.19.0
lib sh.calvin.reorderable:reorderable 3.1.0
lib com.mikepenz:aboutlibraries-core 15.3.0
lib com.mikepenz:aboutlibraries-compose-m3 15.3.0
lib org.jetbrains.kotlin:kotlin-test 2.4.21
lib org.junit.jupiter:junit-jupiter 6.1.3
lib org.junit.platform:junit-platform-launcher 6.1.3
lib junit:junit 4.13.2
lib io.kotest:kotest-assertions-core 6.2.6
lib io.kotest:kotest-property 6.2.6
lib app.cash.turbine:turbine 1.2.1
lib com.squareup.okhttp3:mockwebserver3 5.5.1
lib app.cash.sqldelight:sqlite-driver 2.4.1
lib org.robolectric:robolectric 4.17
lib io.github.takahirom.roborazzi:roborazzi 1.77.0
lib io.github.takahirom.roborazzi:roborazzi-compose 1.77.0
lib androidx.test:runner 1.7.0
lib androidx.test.ext:junit 1.3.0
lib androidx.test.espresso:espresso-core 3.7.0
lib androidx.test.espresso:espresso-intents 3.7.0
lib androidx.test.uiautomator:uiautomator 2.4.1
lib androidx.benchmark:benchmark-macro-junit4 1.5.0
lib com.lemonappdev:konsist 0.18.1
lib com.squareup.leakcanary:leakcanary-android 2.14
```

### Build and plugins

| Artifact | Version | Released | License | Purpose |
|---|---|---|---|---|
| Gradle wrapper | 9.9.0 | 2026-11-06 | Apache-2.0 | Build tool (9.9.1 of 2026-11-10 is too new; Mihon uses 9.9.1) |
| `org.jetbrains.kotlin` plugins: `multiplatform`, `android`, `plugin.compose`, `plugin.serialization` (`kotlin-gradle-plugin`) | 2.4.21 | 2026-10-08 | Apache-2.0 | Kotlin compiler, Compose compiler, serialization (as Mihon) |
| `com.android.tools.build:gradle` (AGP: `application`, `library`, `kotlin.multiplatform.library`, `test`) | 9.5.1 | 2026-11-05 | Apache-2.0 | Android build (as Mihon) |
| `app.cash.sqldelight` gradle plugin | 2.4.1 | 2026-10-06 | Apache-2.0 | DB code generation |
| `dev.zacsweers.metro` gradle plugin | 1.5.2 | 2026-11-03 | Apache-2.0 | Compile-time DI (as Mihon) |
| `com.diffplug.spotless` | 8.11.0 | 2026-11-02 | Apache-2.0 | Format check (as Mihon) |
| `com.pinterest.ktlint:ktlint-cli` (via Spotless) | 1.8.0 | 2025-11-14 | MIT | Kotlin style (as Mihon) |
| ~~`io.gitlab.arturbosch.detekt`~~ | — | — | — | **Removed (D-111):** the no-comments and no-magic-number rules are tooling tests in `apps/android/tooling-tests` (own Kotlin lexer), so detekt's Kotlin 2.4 compatibility risk (F-KT-10) is avoided |
| `androidx.baselineprofile` | 1.5.0 | 2026-09-09 | Apache-2.0 | Baseline profiles |
| `com.mikepenz.aboutlibraries.plugin.android` | 15.3.0 | 2026-10-13 | Apache-2.0 | License list for About (Mihon attribution) |
| `io.github.takahirom.roborazzi` | 1.77.0 | 2026-10-22 | Apache-2.0 | Screenshot tests (1.79.0 too new) |

### Kotlin and KMP libraries (shared core)

| Artifact | Version | Released | License | Purpose |
|---|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` / `-android` / `-test` | 1.11.0 | 2026-05-07 | Apache-2.0 | Coroutines |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.12.0 | 2026-10-19 | Apache-2.0 | JSON (contract, payloads, settings). 1.13.0 (2026-11-11) too new; Mihon uses it |
| `org.jetbrains.kotlinx:kotlinx-datetime` | 0.8.0 | 2026-05-07 | Apache-2.0 | Time in `commonMain` (not the `-0.6.x-compat` variant) |
| `org.jetbrains.kotlinx:kotlinx-io-core` | 0.9.2 | 2026-10-30 | Apache-2.0 | Byte I/O in `commonMain` (`ByteSource`, zip). 0.9.3 too new |
| `com.squareup.okio:okio` | 3.18.2 | 2026-09-04 | Apache-2.0 | File/stream I/O (Android, OkHttp) |
| `org.jetbrains:markdown` | 0.7.18 | 2026-10-15 | Apache-2.0 | GFM parser for `:shared:markdown` (we render the AST ourselves to keep today's restrictions) |

### Crypto (chosen by spike K003, D-110)

| Artifact | Version | Released | License | Purpose |
|---|---|---|---|---|
| `com.goterl:lazysodium-android` (androidMain) | 5.2.0 | 2025-05-31 | MPL-2.0 (libsodium ISC) | libsodium 1.0.20 for Android behind our `Sodium` `expect`/`actual`; its transitive JNA **jar** is excluded |
| `com.goterl:lazysodium-java` (jvmMain, tests) | 5.2.0 | 2025-05-30 | MPL-2.0 | Same libsodium 1.0.20 on the JVM. Ships Java 21 class files: JVM test tasks run on the installed JDK 21 (toolchain, no download); our bytecode stays 17 |
| `net.java.dev.jna:jna` | 5.19.1 (`@aar` on Android, jar on JVM) | 2026-06-12 | Apache-2.0 (dual LGPL-2.1) | JNA runtime for lazysodium |
| transitive: `com.goterl:resource-loader` 2.0.2/2.1.0, `org.slf4j:slf4j-api` (JVM only), `androidx.core:core-ktx` | as resolved | — | Apache-2.0 / MIT | Accepted as lazysodium's own dependencies |

Spike K003 evidence (2026-11-14): 38/38 vectors on JVM and on the emulator; Argon2id 256 MiB × 16
in ~2.5 s on the emulator; adds ~0.83–0.87 MB of native code + jars per ABI before R8; every
`.so` is 16 KB page aligned. `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` 0.9.5 was
rejected: its `PasswordHash.pwhash(String, …)` passes the UTF-16 length, so non-ASCII passwords
hash truncated (F-KT-15), and its JVM jar bundles libsodium 1.0.19 against 1.0.20 on Android.

### AndroidX, Compose and Android libraries

| Artifact | Version | Released | License | Purpose |
|---|---|---|---|---|
| `androidx.compose:compose-bom` | 2026.09.00 | 2026-09-09 | Apache-2.0 | Compose versions (UI/foundation 1.12.1, material3 1.4.0). See F-KT-03 for Mihon's Expressive theme |
| `androidx.compose.material3:material3`, `material3-adaptive-navigation-suite` | 1.4.0 (BOM) | 2025-09-24 | Apache-2.0 | Material 3 components, NavigationSuiteScaffold |
| `androidx.activity:activity-compose` | 1.13.0 | 2026-03-11 | Apache-2.0 | Compose activity, edge to edge |
| `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `lifecycle-process` | 2.11.0 | 2026-06-17 | Apache-2.0 | ViewModels, lifecycle, process foreground/background (auto-lock) |
| `androidx.core:core-ktx` | 1.19.1 | 2026-09-23 | Apache-2.0 | Core APIs, FileProvider, WindowInsetsControllerCompat |
| `androidx.core:core-splashscreen` | 1.2.0 | 2025-11-05 | Apache-2.0 | Splash screen (as Mihon) |
| `androidx.biometric:biometric` | 1.1.0 | 2021-01-27 | Apache-2.0 | BiometricPrompt + CryptoObject (legacy MK pickup, new vault). Last **stable**; 1.4.0-beta01 exists, Mihon uses 1.2.0-alpha05 (F-KT-05) |
| `androidx.documentfile:documentfile` | 1.1.0 | 2025-05-07 | Apache-2.0 | SAF folder writes (export) |
| `androidx.profileinstaller:profileinstaller` | 1.4.1 | 2024-10-02 | Apache-2.0 | Installs baseline profiles |
| `androidx.media3:media3-exoplayer`, `media3-ui-compose`, `media3-ui` | 1.11.1 | 2026-09-11 | Apache-2.0 | Video and voice playback, decrypting DataSource |
| `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-video`, `camera-compose` | 1.6.2 | 2026-08-26 | Apache-2.0 (camera-core also BSD-3-Clause) | Built-in camera |
| transitive: `androidx.media3:media3-muxer`/`-common`/`-container` 1.9.0, `com.google.guava:guava` 32.0.1-android, `com.google.dagger:dagger` 2.59, `androidx.camera:camera-camera2-pipe`, `org.jetbrains.kotlinx:atomicfu` | as resolved | — | Apache-2.0 | Accepted as CameraX's own dependencies (pinned by the lockfiles and verification metadata) |
| `cafe.adriel.voyager:voyager-navigator`, `voyager-tab-navigator`, `voyager-transitions` | 2.2.21-1.10.3 | 2026-06-08 | MIT | Navigation (D-107, as Mihon; Maven Central) |
| `dev.zacsweers.metro:runtime`, `metrox-viewmodel-compose` | 1.5.2 | 2026-11-03 | Apache-2.0 | DI runtime + ViewModel factory (as Mihon) |
| `com.squareup.okhttp3:okhttp` | 5.5.1 | 2026-10-22 | Apache-2.0 | HTTP (API, presigned PUT/GET, uploads) |
| `app.cash.sqldelight:android-driver`, `coroutines-extensions` | 2.4.1 | 2026-10-06 | Apache-2.0 | Database over the framework SQLite (opens the v0.3.1 file in place) |
| `io.coil-kt.coil3:coil`, `coil-compose` | 3.6.3 | 2026-09-18 | Apache-2.0 | Image loading with our decrypting fetchers (no `coil-network-*`) |
| `me.saket.telephoto:zoomable-image-coil3` | 0.19.0 | 2026-04-02 | Apache-2.0 | Pinch/double-tap zoom in the viewer |
| `sh.calvin.reorderable:reorderable` | 3.1.0 | 2026-04-20 | Apache-2.0 | Drag-reorder in the tag editor (as Mihon) |
| `com.mikepenz:aboutlibraries-core`, `aboutlibraries-compose-m3` | 15.3.0 | 2026-10-13 | Apache-2.0 | Licenses screen |

### Adapted code, palettes and icons (not libraries)

| Source | License | Where it is used |
|---|---|---|
| [Mihon](https://github.com/mihonapp/mihon) | Apache-2.0 | UI code adapted from Mihon (theme, components, settings widgets, screens); each adapted file starts with the two-line attribution header (guidelines §4.1); Settings › About shows the attribution |
| Mihon's color schemes (via akon-money) | Apache-2.0 | The thirteen extra theme presets in Appearance (Green Apple, Lavender, Midnight Dusk, Nord, Strawberry, Tako, Teal & Turquoise, Tidal Wave, Tokyo Night, Yin & Yang, Yotsuba, Monochrome, Catppuccin), in `ui/core/.../theme/colorscheme/ColorSchemeConstants.kt` |
| [Catppuccin](https://github.com/catppuccin/catppuccin) palette (via Mihon) | MIT | The Catppuccin theme preset's colors |
| Google Material icons | Apache-2.0 | The 24 dp vector drawables `ic_*.xml` in `:ui:core` (settings, edit page, debug log icons) |

### Allowlisted but not in the catalog

These rows above are allowed but no module uses them in v0.4.1, so they are not in
`libs.versions.toml` and not in the APK: the serialization and `com.android.test` plugins,
`androidx.baselineprofile`, AboutLibraries (plugin, `aboutlibraries-core`, `-compose-m3`; the license
list is generated instead), `kotlinx-datetime`, `kotlinx-io-core`, `okio` (direct), `org.jetbrains:markdown`
(`:shared:markdown` has its own parser that matches v0.3.1's marked output), Compose `ui-tooling` and
`ui-tooling-preview`, `material3-adaptive-navigation-suite`, `core-splashscreen`, `profileinstaller`
(direct), SQLDelight `coroutines-extensions` and `sqlite-driver`, `coil-compose`, telephoto, and the
test/debug rows `kotest-property`, Turbine, Espresso, UI Automator, Macrobenchmark, Konsist (module
rules are `tooling-tests` that read the Gradle files) and LeakCanary.

### Kotlin test and debug tooling

| Artifact | Version | Released | License | Purpose |
|---|---|---|---|---|
| `org.jetbrains.kotlin:kotlin-test` | 2.4.21 | 2026-10-08 | Apache-2.0 | KMP `commonTest` |
| `org.junit.jupiter:junit-jupiter` | 6.1.3 | 2026-08-07 | EPL-2.0 (test only) | JVM unit tests (as Mihon) |
| `io.kotest:kotest-assertions-core`, `kotest-property` | 6.2.6 | 2026-10-14 | Apache-2.0 | Assertions, property tests (6.2.7 too new) |
| `app.cash.turbine:turbine` | 1.2.1 | 2025-06-11 | Apache-2.0 | Flow tests |
| `com.squareup.okhttp3:mockwebserver3` | 5.5.1 | 2026-10-22 | Apache-2.0 | HTTP tests |
| `app.cash.sqldelight:sqlite-driver` | 2.4.1 | 2026-10-06 | Apache-2.0 | JVM tests over the v0.3.1 fixture database |
| `org.robolectric:robolectric` | 4.17 | 2026-09-10 | MIT | JVM Android tests (4.18 too new) |
| `io.github.takahirom.roborazzi:roborazzi`, `roborazzi-compose` | 1.77.0 | 2026-10-22 | Apache-2.0 | Screenshot tests |
| `androidx.compose.ui:ui-test-junit4`, `ui-test-manifest` | 1.12.1 (BOM) | 2026-09-09 | Apache-2.0 | Compose UI tests |
| `androidx.test:runner` 1.7.0, `androidx.test.ext:junit` 1.3.0, `androidx.test.espresso:espresso-intents` 3.7.0 | as listed | 2025-07-30 | Apache-2.0 | Instrumented tests, intent checks (share, pickers) |
| `androidx.test.uiautomator:uiautomator` | 2.4.1 | 2026-11-04 | Apache-2.0 | Macrobenchmark journeys |
| `androidx.benchmark:benchmark-macro-junit4` | 1.5.0 | 2026-09-09 | Apache-2.0 | Macrobenchmark + baseline profile generation |
| `com.lemonappdev:konsist` | 0.18.1 | 2026-10-10 | Apache-2.0 | Architecture rules (module boundaries, strings/test tags) |
| `com.squareup.leakcanary:leakcanary-android` (debug only) | 2.14 | 2024-04-18 | Apache-2.0 | Leak checks (as Mihon; 3.0 is alpha) |
| `gradle/actions/wrapper-validation` | v6.4.1 `8da46b539cd8fe6ab27ca75128919a18d757604c` | 2026-10-28 | MIT | CI wrapper check (same release as `setup-gradle`) |

Tool, not a dependency: Maestro (already used for Android flows).

### Kotlin: deliberately excluded

| Artifact | Reason |
|---|---|
| JitPack and anything only there (Mihon's `unifile`, its SubsamplingScaleImageView fork) | Unpinned builds from Git; `androidx.documentfile` and telephoto cover the needs |
| `androidx.compose:compose-bom-beta` / material3 1.5.0-rc01 | Not stable; Mihon's `MaterialExpressiveTheme` is adapted to `MaterialTheme` until material3 1.5.0 stable is ≥ 7 days old (F-KT-03) |
| `androidx.compose.material:material-icons-extended` | Frozen at 1.7.8 (2025-02-12) and large; the needed Material icons are copied as 24 dp vector drawables (`ui/core/src/main/res/drawable/ic_*.xml`, Apache-2.0) |
| Navigation 3 | D-107 chose Voyager |
| detekt | Source rules are tooling tests (D-111) |
| `io.github.fornewid:material-motion-compose-core` (Mihon's `materialSharedAxisX`) | Not needed: the shared-axis screen transition is rebuilt with plain Compose slide + fade (spike K004) |
| `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` | Rejected in spike K003 (F-KT-15) |
| Hilt, Dagger, Koin | Metro (compile-time) |
| Room, DataStore, SharedPreferences for settings | The v0.3.1 database and `settings.device` JSON are adopted as they are (P3) |
| `androidx.sqlite:sqlite-bundled`, `com.eygraber:sqldelight-androidx-driver` | The framework SQLite opens the legacy file; no second SQLite build in the APK |
| `com.mikepenz:multiplatform-markdown-renderer` | Renders HTML-ish features we refuse (images, tables); own renderer over `org.jetbrains:markdown` |
| `io.mockk:mockk` | Fakes behind interfaces (guidelines §12) |
| Firebase, Crashlytics, any telemetry | E2EE journal: no third-party telemetry |
| `androidx.exifinterface` | `:shared:media` already parses EXIF (ported, tested) |

## CI gates (guidelines 12, D-11)

1. `pnpm lint` (zero warnings), `pnpm typecheck`, `pnpm test`.
2. `pnpm audit --audit-level moderate`: fails on moderate or higher.
3. `osv-scanner scan -L pnpm-lock.yaml` (v2.7.1, F-TOOL-06): fails on any unreviewed advisory.
4. `pnpm licenses list --json --prod`: every license must be one of MIT, ISC, BSD-2-Clause,
   BSD-3-Clause, Apache-2.0, 0BSD, BlueOak-1.0.0, CC0-1.0, Unlicense, MPL-2.0 (file-level copyleft,
   fine for unmodified use), Python-2.0, CC-BY-4.0 (D-70; `scripts/check-licenses.ts`, deployment).
5. `pnpm sbom --prod --out sbom.cdx.json` attached to each tagged release.
6. Renovate PRs with `minimumReleaseAge: "7 days"` (F-TOOL-06); crypto-package updates need a
   human review note.

## Review cadence

Every row is reviewed when Renovate bumps it; the `Status` line carries the review date.
Quarterly: re-check audit statuses (new audits of libsodium and its bindings).
