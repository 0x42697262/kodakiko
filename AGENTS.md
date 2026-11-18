# AGENTS.md — Kodakiko

Kodakiko is an end-to-end encrypted photo and video journal: entries with photos, videos, voice notes
and a markdown note; tags; gallery and calendar; sharing and public links; a native Android app in
Kotlin (Jetpack Compose, Mihon-based UI, `apps/android`) and a self-hosted TypeScript server
(`apps/server`). Public links open in the standalone link viewer (`apps/link-viewer`) served by
Caddy. There is no web app. It is implemented and released (see `app-version-name` in
`apps/android/gradle/libs.versions.toml`).

## Read first

1. `docs/engineering-guidelines.md`: the rules for every change.
2. `docs/README.md`: the document map. `docs/architecture.md` for the code layout,
   `docs/features/<area>.md` for how the area you touch works.
3. `docs/facts.md`: confirmed facts about third-party libraries and services (F-XXX-NN). Add a row
   when you confirm a new one, with its source.

## How changes are made

1. **Test first.** Write the tests for the change, run them, and see them fail for the expected
   reason (a missing class or a wrong result, not a broken setup). Then implement until they
   pass. A bug fix starts with a failing test that reproduces it. (The code-first exception of
   D-165 covered only the v0.4.1 phone-feedback batch; it is over.)
2. **Tests are not weakened.** Existing tests are never deleted, skipped (`@Ignore`, `.skip`,
   commented out, excluded by config) or loosened (weaker assertions, longer timeouts, broader
   matchers, re-recorded screenshots without a reviewed reason) to make code pass. When the
   programmer changes a behavior, the test changes with it, and the feature doc says what changed.
3. **Verify.** `./gradlew verify` (in `apps/android`) and `pnpm verify` must pass; run the UI flows
   the change touches (`docs/testing.md`). Anything not run, or only checkable on a phone, is
   reported as UNVERIFIED.
4. **Docs with the code.** Update `docs/features/<area>.md` (and the guide, if users see the change)
   in the same change, in its own commit.
5. **Dependencies:** Kotlin libraries only from the Gradle catalog
   `apps/android/gradle/libs.versions.toml`, TypeScript packages only from the `pnpm-workspace.yaml`
   catalog (`"catalog:"` in the package); released at least 7 days earlier, each listed with its
   reason in `docs/dependencies.md`.

## Code rules (details in the guidelines)

### Android app (`apps/android`)

- Module map and layers: `docs/architecture.md` (ADR 0011). Shared logic lives in Kotlin
  Multiplatform `:shared:*` modules (`commonMain`, no Android or Java APIs); Android I/O in
  `:data:*` and `:platform:*`; business logic in `:service:*`; UI in `:ui:core` and
  `:ui:feature:*`; wiring in `:app`. A module depends only on lower layers, and feature modules
  never depend on each other (`tooling-tests` checks it).
- No comments. The only allowed comment is the two-line attribution header on files adapted from
  Mihon: `// Adapted from https://github.com/mihonapp/mihon` and
  `// Copyright 2015 Javier Tomás, 2024 Mihon Open Source Project, Apache-2.0`.
- No magic values: numbers, durations and color literals in `*Constants.kt` objects; UI text in
  string resources (`ui/core/src/main/res/values/`: `strings.xml` is generated from the v0.3.1 text
  and checked by `node tests/fixtures/ui-strings/emit-android.ts --check`; new text goes in
  `strings_v040.xml`, edit-page text of v0.4.1 in `strings_v041_edit.xml`); test ids in
  `TestTags` (`:ui:core`, plus `:app` for shell ids). `tooling-tests` rejects comments, magic
  numbers and literal UI text.
- Screens follow Mihon: a stateful `XScreen` with its ViewModel and a stateless `XContent`, which
  is what the Compose and Roborazzi tests render. UI state classes are `@Immutable`.
- Secrets are `ByteArray`, wiped after use; a password or passphrase may arrive as `String` from a
  text field and is converted once at the core API boundary. No I/O on the main thread.
- `./gradlew verify` must pass next to `pnpm verify`.

### TypeScript (`apps/server`, `apps/link-viewer`, `packages/*`)

- No explanatory comments; no magic numbers or strings outside constants modules (ESLint enforces
  both).
- `packages/core` stays pure (no Node or browser APIs). A new area under `packages/core/src/` gets
  an `index.ts` barrel and one `export *` line in `packages/core/src/index.ts`; consumers import
  only from `@kodakiko/core`.

### Everywhere

- Never print, log or commit secrets. American English everywhere.

## Working with the programmer

- The programmer has ADHD and prefers "ADHD mode" responses: lead with the next action, numbered
  steps, at most 5 items per list, restate where we are each turn, concrete time estimates, no
  preamble, no recap, no closing pleasantries. Turned off only by "stop adhd mode" or "normal mode".
- Ask questions in rounds of at most 5, recommended option first, answerable with letters.
- Planning notes (decisions D-NN, interviews, version plans) live in `docs/planning/`, which is
  ignored by git: keep them up to date locally, never commit them.
- Commit only the paths you changed; the programmer pushes.
