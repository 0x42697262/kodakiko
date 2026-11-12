# Dependency allowlist (D-11)

Status: draft 2 (2026-11-11, after review 1). **Sonnet may not add, replace or bump any dependency
not listed here.** Adding one is an Opus/programmer decision recorded in this file with a review
date.

## Version rule

Every version below is the **newest release published at least 7 days before 2026-11-11**
(cutoff 2026-11-04), so it satisfies `minimumReleaseAge` (7 days) on the day it was chosen. Pin
exactly these versions. If an install happens much later, Sonnet does **not** pick newer versions;
it reports "allowlist is N days old" and Opus refreshes this file by the same rule.

Expo-family packages follow Expo SDK 58's `bundledNativeModules.json` (F-TOOL-12). Where the SDK
asks for a patch newer than the age cutoff (`expo-router ~58.0.31`, `expo-sqlite ~58.0.16`,
`expo-video ~58.0.14`), the older compliant patch below is pinned and the resulting
`npx expo install --check` patch-level warning is accepted. A minor or major mismatch stops the
work and goes to Opus.

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
  script). When `pnpm install` stops with `ERR_PNPM_IGNORED_BUILDS` for another package, Sonnet
  adds it **only if that package is on this allowlist**, with `true` if its build is needed for the
  app to run (native binary download) and `false` otherwise, and records the line here. Anything
  else stops the work.
- `packageManager` in the root `package.json`: `pnpm@12.17.0` (2026-10-30, compliant).

## Allowlist

### Crypto

| Package | Version | Used by | Purpose | Audit status |
|---|---|---|---|---|
| `react-native-libsodium` | 1.7.0 | client (native) | libsodium via JSI (F-LIB-01/02) | wrapper unaudited; libsodium 1.0.21 inside is audited lineage (F-LIB-04). Mitigation: shared known-answer vectors incl. Argon2id (crypto.md §10) |
| `libsodium-wrappers-sumo` | 0.8.4 | client (web), core tests | libsodium WASM; sumo for Argon2id (F-LIB-03) | libsodium audited (F-LIB-04) |

The server uses **no** crypto package (`node:crypto` only, architecture.md §4.1).

### Shared tooling (root devDependencies unless stated)

| Package | Version | Purpose |
|---|---|---|
| `typescript` | 6.0.3 | type checking. **Not 7.x**: `typescript-eslint` 8.72 requires `<6.1.0` (F-TOOL-11) |
| `vitest` | 5.1.0 | tests (needs Node ^22.12 or ≥24) |
| `eslint` | 10.14.0 | lint |
| `typescript-eslint` | 8.72.1 | TypeScript lint rules |
| `prettier` | 3.9.10 | formatting |
| `esbuild` | 0.28.2 | server bundle (architecture.md §4.1), service worker bundle (storage.md §5) |
| `@types/node` | 22.20.5 | server and scripts |
| `zod` | 4.6.7 | runtime dependency of contract, core, server, client |

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

`@fastify/cors` was removed in review 1: the web app calls the API on its own origin
(deployment.md §2), and the Android app is not subject to CORS.

### Client (Expo SDK 58 family)

| Package | Version | Purpose |
|---|---|---|
| `expo` | 58.0.23 | platform |
| `expo-router` | 58.0.30 | navigation (client.md §1) |
| `react` / `react-dom` | 19.3.0 | UI; matches Expo SDK 58 (F-TOOL-12): react-native 0.88.1's bundled renderer is built against 19.3.0 (`ReactFabric-*.js` `version: "19.3.0"`), and `react-dom` must equal `react` exactly |
| `react-native` | 0.88.1 | UI |
| `react-native-web` | 0.21.4 | web target |
| `@expo/metro-runtime` | 58.0.17 | web runtime for Expo Router |
| `expo-linking` | 58.0.16 | required peer of `expo-router` |
| `@expo/log-box` | 58.0.15 | required peer of `expo-router` |
| `react-native-screens` | 4.30.1 | required peer of `expo-router` |
| `react-native-safe-area-context` | 5.11.1 | required peer of `expo-router`; `SafeAreaProvider` |
| `react-native-gesture-handler` | 3.3.0 | pinch, pan and double-tap gestures for photo zoom (D-59); SDK 58 bundled version |
| `react-native-reanimated` | 4.7.1 | gesture-driven transforms on the UI thread (D-59); peer `react-native` 0.86-0.88 |
| `react-native-worklets` | 0.13.1 | required peer of reanimated 4 (`0.13.x`); SDK 58 asks `0.13.0`, 0.13.1 is a compliant patch |
| `expo-constants` | 58.0.15 | app version display |
| `expo-file-system` | 58.0.13 | FileHandle chunked I/O (F-LIB-05) |
| `expo-sqlite` | 58.0.15 | RowStore, plain (D-37); no SQLCipher plugin |
| `expo-secure-store` | 58.0.2 | KeyVault (F-LIB-13) |
| `expo-local-authentication` | 58.0.2 | biometrics (F-LIB-13) |
| `expo-image` | 58.0.18 | display, cache, dimensions (F-LIB-09) |
| `expo-image-picker` | 58.0.10 | Android Photo Picker import (F-LIB-12) |
| `expo-image-manipulator` | 58.0.16 | thumbnail resize + WebP encode, link-copy re-encode (media.md §3, §6) |
| `expo-video` | 58.0.13 | Android playback (temp file) and video metadata |
| `expo-video-thumbnails` | 58.0.2 | Android video poster frame (media.md §3) |
| `expo-network` | 58.0.4 | network type (Wi-Fi or not) for the large-upload rule and pinned downloads (D-61); SDK 58 asks `~58.0.5` (patch newer than the age cutoff, accepted) |
| `expo-location` | 58.0.20 | opt-in device location for new camera captures (D-55); SDK 58 asks `~58.0.21` (patch newer than the age cutoff, accepted per the version rule) |
| `react-native-vision-camera` | 5.2.4 | Android camera (F-LIB-06) |
| `react-native-nitro-modules` | 0.37.2 | peer of vision-camera |
| `react-native-nitro-image` | 0.15.2 | peer of vision-camera |
| `@shopify/flash-list` | 2.3.2 | gallery grid (F-LIB-10); 2.3.2 is the SDK 58 bundled version |
| `idb` | 8.0.4 | web RowStore (IndexedDB wrapper) |
| `@types/react` | 19.3.0 | dev: React 19 ships no type definitions; needed by the client type check |

Removed in review 1: `expo-clipboard` (web paste uses DOM events), `expo-application`, `tsx`.

### Client libraries decided after review 1

| Package | Version | Purpose | Decision |
|---|---|---|---|
| `marked` | 18.1.3 | markdown **lexer only** for notes; tokens rendered by our code; HTML output never used | D-51 (F-LIB-16) |
| `fflate` | 0.8.3 | streaming zip writer for the journal export, behind the `ArchiveWriter` port | D-52 (F-LIB-17) |

### Test tooling (spec 07c, D-74)

| Package / tool | Version | Why |
|---|---|---|
| `@playwright/test` | 1.64.1 (2026-10-14) | web UI flows; devDependency of `tests/ui` only; its Chromium build is downloaded by `playwright install chromium` (tied to this version) |
| Maestro CLI | 2.12.1 (2026-11-02) | Android UI flows; not an npm package; downloaded by `tests/ui/android/install-maestro.sh` and checked against SHA-256 `54dbb0716425626604973cbb374c585ca30272375d9ce15177a1152765ddd9d7`; Apache-2.0 |
| ffmpeg | ≥ 8.0 (developer machine) | generates the committed media fixtures once (`tests/fixtures/media/generate.sh`); never installed by the project, never in an image |

`tests/ui` also uses `fflate` and `libsodium-wrappers-sumo` (already allowlisted).

### Container images

Pinned by digest when first pulled; the digest is recorded here in the same commit.

| Image | Tag | Digest | Purpose |
|---|---|---|---|
| `postgres` | `17` (latest 17.x patch at pull time) | to record | database (`deployment.md` §2), also the test database (spec 03) |
| `dxflrs/garage` | `v2.5.0` | to record | object storage (F-S3-02) |
| `caddy` | `2` builder + runtime | to record | TLS + reverse proxy, built with the DNS provider plugin (spec 21) |
| `node` | `22-bookworm-slim` | to record | server runtime and build stages (spec 21) |
| `caddy` | `2-builder` | to record | xcaddy build with `CADDY_VERSION` and `DNS_MODULE` pinned (spec 21, D-68) |
| `rclone/rclone` | latest 1.x at pull time | to record | bucket backups (spec 21, operations guide) |

### Deliberately excluded

| Package | Reason |
|---|---|
| `exifr` | stale since 2021, React Native undocumented (F-LIB-15); in-repo bounded EXIF reader instead (media.md §4.1) |
| `op-sqlite` | SQLCipher + FTS5 combination undocumented (F-LIB-08); not needed after D-37 |
| `react-native-background-upload` | unmaintained since 2022 (F-LIB-14); `packages/native-upload` instead |
| `expo-background-task` | WorkManager runs at most every 15 min (F-LIB-14); the foreground upload service covers uploads (sync.md §8) |
| `tus-js-client` | foreground-only, extra protocol; presigned multipart is enough (storage.md §3) |
| MinIO server image | unmaintained (F-S3-01); Garage instead |
| `@tanstack/react-query` | the local-first index replaces a server-state cache in v1 (client.md §3) |
| `react-native-markdown-display` | stale (F-LIB-16) |
| Any SRP library | ADR 3 |
| Any BIP-39 library | word list committed as data (crypto.md §5.6) |
| `typescript` 7.x | breaks `typescript-eslint` (F-TOOL-11) |

## CI gates (guidelines 12, D-11)

1. `pnpm lint` (zero warnings), `pnpm typecheck`, `pnpm test`.
2. `pnpm audit --audit-level moderate`: fails on moderate or higher.
3. `osv-scanner scan -L pnpm-lock.yaml` (v2.7.1, F-TOOL-06): fails on any unreviewed advisory.
4. `pnpm licenses list --json --prod`: every license must be one of MIT, ISC, BSD-2-Clause,
   BSD-3-Clause, Apache-2.0, 0BSD, BlueOak-1.0.0, CC0-1.0, Unlicense, MPL-2.0 (file-level copyleft,
   fine for unmodified use), Python-2.0, CC-BY-4.0 (D-70; `scripts/check-licenses.ts`, spec 21).
5. `pnpm sbom --prod --out sbom.cdx.json` attached to each tagged release.
6. Renovate PRs with `minimumReleaseAge: "7 days"` (F-TOOL-06); crypto-package updates need a
   human review note.

## Review cadence

Every row is reviewed when Renovate bumps it; the `Status` line carries the review date.
Quarterly: re-check audit statuses (react-native-libsodium PR #108, new audits of libsodium or
Expo).
