# Development setup

## Toolchain

- Node ≥ 22.18 and any pnpm ≥ 11 (it switches to the pinned 12.17.0 by itself).
- Docker for the test, dev and UI stacks.
- Android builds: `nix develop` (flake.nix, D-80) gives the Android SDK (platform 37, build-tools
  37.0.0 and 36.0.0, NDK 27.0.12077973, CMake 3.22.1) and JDK 17. A first debug build needs about
  6 GB of free disk (Gradle cache plus native intermediates under `node_modules/*/android/`); delete
  those intermediates afterwards with `pnpm --filter @kodakiko/client clean:android`.
- Emulator: the existing `akon` AVD (API 34, x86_64, KVM) from the akon-money project
  (`nix develop` there, then `emu-start`).

## Local dev stack (spec 07b §1)

1. `docker compose -p kodakiko-dev -f infra/compose/docker-compose.dev.yml up -d --wait`
2. `sh infra/compose/dev/bootstrap-garage.sh` (idempotent; creates `apps/server/.env.dev` from the
   example if missing and writes the S3 keys into it without printing them).
3. `node --env-file=apps/server/.env.dev apps/server/dist/cli.js apply-bucket-cors`
4. `pnpm --filter @kodakiko/server dev`, then `pnpm --filter @kodakiko/client start:web`; open
   `https://localhost:9443`.
5. Android: `docker cp kodakiko-dev-caddy-dev-1:/data/caddy/pki/authorities/local/root.crt .`,
   install it as a user CA on the device, then `adb reverse tcp:9443 tcp:9443` and
   `adb reverse tcp:9444 tcp:9444`; enter `https://localhost:9443` on `/welcome`.

Security note: the dev API listens on `0.0.0.0:8080` so the Caddy container can reach it. Block
port 8080 from the LAN with the host firewall (it trusts forwarded headers from private addresses).
Caddy's dev ports are bound to `127.0.0.1` only.

## UI tests (spec 07c)

- Web: `pnpm ui:web`.
- Android: start the emulator, then `pnpm ui:android [U-NN…]` (`UI_SKIP_BUILD=1` reuses the
  installed build).

## Screenshots

`pnpm --filter @kodakiko/ui-tests screenshots` regenerates `docs/screenshots/*.png` (needs
ImageMagick and ffmpeg for the demo media; see `docs/screenshots/README.md`).
