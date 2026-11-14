# AGENTS.md — Kodakiko

Kodakiko is an end-to-end encrypted photo and video journal: entries with photos, videos, voice
notes and a markdown note; tags; gallery and calendar; sharing and public links; an Android app and
a web app from one Expo codebase, with a self-hosted server. It is implemented and released
(see `APP_VERSION` in `apps/client/app.config.ts`).

## Read first

1. `docs/engineering-guidelines.md`: the rules for every change.
2. `docs/README.md`: the document map. `docs/architecture.md` for the code layout,
   `docs/features/<area>.md` for how the area you touch works.
3. `docs/facts.md`: confirmed facts about third-party libraries and services (F-XXX-NN). Add a row
   when you confirm a new one, with its source.

## How changes are made

1. **Test first.** Write the tests for the change, run them, and see them fail for the expected
   reason (a missing module or a wrong result, not a broken setup). Then implement until they
   pass. A bug fix starts with a failing test that reproduces it.
2. **Tests are not weakened.** Existing tests are never deleted, skipped (`.skip`, `.todo`,
   `test.fixme`, commented out, excluded by config) or loosened (weaker assertions, longer
   timeouts, broader matchers) to make code pass. When the programmer changes a behavior, the test
   changes with it, and the feature doc says what changed.
3. **Verify.** `pnpm verify` must pass; run the UI flows the change touches (`docs/testing.md`).
   Anything not run, or only checkable on a phone, is reported as UNVERIFIED.
4. **Docs with the code.** Update `docs/features/<area>.md` (and the guide, if users see the change)
   in the same change, in its own commit.
5. **Dependencies:** only packages in the `pnpm-workspace.yaml` catalog (`"catalog:"` in the
   package), released at least 7 days earlier, each listed with its reason in
   `docs/dependencies.md`.

## Code rules (details in the guidelines)

- No explanatory comments; no magic numbers or strings outside constants modules (ESLint enforces
  both). Strings in `apps/client/src/text/en.ts`, test ids in `src/keys/`, tuning in
  `src/constants/`.
- `packages/core` stays pure (no React, Expo, Node or browser APIs). A new area under
  `packages/core/src/` gets an `index.ts` barrel and one `export *` line in
  `packages/core/src/index.ts`; consumers import only from `@kodakiko/core`.
- Client platform code: `name.native.ts` + `name.web.ts` + `name.ts` (declaration); a platform file
  never imports its own base name, shared code goes in `name-shared.ts`.
- Raw `Pressable` only inside `apps/client/src/ui/`; use `Touchable`.
- Never print, log or commit secrets. American English everywhere.

## Working with the programmer

- The programmer has ADHD and prefers "ADHD mode" responses: lead with the next action, numbered
  steps, at most 5 items per list, restate where we are each turn, concrete time estimates, no
  preamble, no recap, no closing pleasantries. Turned off only by "stop adhd mode" or "normal mode".
- Ask questions in rounds of at most 5, recommended option first, answerable with letters.
- Planning notes (decisions D-NN, interviews, version plans) live in `docs/planning/`, which is
  ignored by git: keep them up to date locally, never commit them.
- Commit only the paths you changed; the programmer pushes.
