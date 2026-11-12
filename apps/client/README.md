# @kodakiko/client

Expo app (Android dev build and web) built from one codebase with Expo Router.

## Scripts

Run these from `apps/client`. Never call `pnpm exec expo ...` directly: the web worker must be built first.

| Script | What it does |
|---|---|
| `pnpm build:workers` | Bundles the Argon2id Web Worker into `public/workers/argon2.js` |
| `pnpm start:web` | Builds the worker, then serves the web app on port 8081 |
| `pnpm build:web` | Builds the worker, then exports the web app to `dist/` |
| `pnpm prebuild:android` | Regenerates the native `android/` project (needs no Android SDK) |
| `pnpm start:metro-ui` | Metro only, for the Android UI test runner (the web worker is not needed) |
| `pnpm run:android` | Builds and installs the Android dev build (Expo Go cannot load JSI modules) |
| `pnpm typecheck` | Type check |
| `pnpm test` | Unit tests (Node) |

## Diagnostics

Dev builds only: open `/settings/diagnostics`. It runs the crypto vectors, measures Argon2id and runs the
RowStore and FileStore self-tests on the current platform.
