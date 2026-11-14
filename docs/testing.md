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
- Planned tests are not deleted, skipped or loosened. When the programmer changes a behavior, the
  test changes with it and the feature spec's implementation notes say so.
- Web flows that need one viewport skip the other in `beforeAll` (`test.skip(...)`), and every flow
  seeds its own account (`uiUsername('U-NN', project, n)`), so flows do not share state.
- Flows must not log console errors or page errors: the fixture fails the test if they do.

## Where things are

| What | Path |
|---|---|
| Flow catalog (U-01 …) with the steps of every flow | `docs/features/07c-ui-test-harness.md` |
| Behavior catalog (Given / When / Then) | `docs/features/behaviors.md` |
| Seeding accounts and entries through the API | `tests/ui/support/seed.ts` |
| Reading what the server stores | `tests/ui/support/server-state.ts` |
| Media fixtures | `tests/fixtures/media/` |

## Not covered by automated tests

These run only on a phone: background uploads, biometric unlock, camera capture and hold to
record, Android ripple, haptics and predictive back, dynamic color, share to apps, clipboard
paste, voice recording with `expo-audio`, crop with `expo-image-manipulator`, and key-derivation
timing. Each feature spec lists its UNVERIFIED items.
