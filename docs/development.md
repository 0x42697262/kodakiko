# Development

## Toolchain

- Node ≥ 22.18 and pnpm (any version ≥ 11 switches to the pinned 12.17.0 by itself).
- Docker with the Compose plugin for the dev, test and UI stacks.
- Android: `nix develop` (`flake.nix`) gives the Android SDK (platform 37, build-tools 37.0.0 and
  36.0.0, NDK 27.0.12077973, CMake 3.22.1), JDK 17, scrcpy and resvg. A first debug build needs
  about 6 GB of free disk; `pnpm --filter @kodakiko/client clean:android` removes the native
  intermediates afterwards.

## Local stack

1. `docker compose -p kodakiko-dev -f infra/compose/docker-compose.dev.yml up -d --wait`
2. `sh infra/compose/dev/bootstrap-garage.sh`: creates `apps/server/.env.dev` from the example if
   it is missing and writes the S3 keys into it without printing them. Safe to run again.
3. `pnpm --filter @kodakiko/server build`, then
   `node --env-file=apps/server/.env.dev apps/server/dist/cli.js apply-bucket-cors`.
4. `pnpm --filter @kodakiko/server dev` and `pnpm --filter @kodakiko/client start:web`; open
   `https://localhost:9443`.
5. First account: `node --env-file=apps/server/.env.dev apps/server/dist/cli.js create-invite --role admin --expires-days 7`.

The dev API listens on `0.0.0.0:8080` so the Caddy container can reach it; block port 8080 from
the LAN with the host firewall. Caddy's dev ports are bound to `127.0.0.1`.

## Android

| Command (in `apps/client`) | What it does |
|---|---|
| `pnpm prebuild:android` | Generates `android/` from `app.config.ts` |
| `pnpm build:android:debug` | Debug APK for the x86_64 emulator |
| `pnpm build:android:device` | Debug APK for an arm64 phone |
| `pnpm run:android` | Builds, installs and starts Metro |
| `pnpm clean:android` | Deletes the native build intermediates |

Against the dev stack: copy Caddy's root certificate
(`docker cp kodakiko-dev-caddy-dev-1:/data/caddy/pki/authorities/local/root.crt .`), install it as
a user CA on the device, `adb reverse tcp:9443 tcp:9443` and `adb reverse tcp:9444 tcp:9444`, then
enter `https://localhost:9443` on the welcome screen. On the emulator the host is `10.0.2.2`.

## Releases

`APP_VERSION` in `apps/client/app.config.ts` sets the version name and the version code
(0.3.0 → 300). Pushing a tag `v*` runs `.github/workflows/android-release.yml`: it builds the
`arm64-v8a` release APK, signs it with the keystore from the repository secrets
(`ANDROID_KEYSTORE_BASE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`)
and publishes it with a `.sha256` as a GitHub release. Pushes to `main` that touch the app run the
same build without publishing, to warm the Gradle cache.

## Working on the client

- Platform code lives in pairs: `name.native.ts` and `name.web.ts`, plus `name.ts` with the shared
  declaration. A platform file must not import its own base name; shared code goes in
  `name-shared.ts`.
- After adding a route under `apps/client/app/`, run `npx expo start` once so
  `.expo/types/router.d.ts` picks it up (typed routes).
- Strings live in `src/text/en.ts`, test ids in `src/keys/`, numbers and tuning in
  `src/constants/`. ESLint rejects comments, magic numbers, raw `Pressable` outside `src/ui/` and
  `allowFontScaling={false}`.
- New dependencies must be in the pnpm catalog (`pnpm-workspace.yaml`, `"catalog:"` in the
  package), released at least 7 days ago, and listed in [dependencies.md](dependencies.md).

## Tests and screenshots

See [testing.md](testing.md). `pnpm --filter @kodakiko/ui-tests screenshots` regenerates
`docs/screenshots/` (needs ImageMagick and ffmpeg; see `docs/screenshots/README.md`).

Engineering rules for every change: [engineering-guidelines.md](engineering-guidelines.md).
