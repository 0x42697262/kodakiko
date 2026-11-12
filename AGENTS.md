# AGENTS.md — Kodakiko

Kodakiko is an end-to-end encrypted photo/video journal: quick capture (several photos/videos per
entry plus a markdown note), tags instead of categories, gallery browsing, multiple users, Android
app first and a web app usable on phones.

## Status

**Implementation, phase 1 (local).** Planning is complete and the programmer has approved coding.
Feature specs live in `docs/features/`; they are implemented strictly in the order of
`docs/features/README.md` (07c comes before 07b), one spec per session. Sonnet may not add
dependencies outside `docs/dependencies.md`.

## Sonnet: each session

1. Find the next spec: the first one in the build order without a `feat(NN): …` commit in `git log`.
2. Read `docs/features/README.md` (rules 1-9), `docs/engineering-guidelines.md` and the spec with
   everything its "Read first" line names.
3. Test first: write the spec's tests and its behavior/UI-flow tests, run them, confirm they fail
   for the expected reason; then implement until they pass. Planned tests are never deleted,
   skipped or loosened (rule 9).
4. Run the spec's verification checklist. Anything not run is marked UNVERIFIED.
5. Stop after that one spec. Do not commit (the programmer commits). End with a report:
   checklist results, the red-then-green test evidence, extra tests added, measurements asked for,
   deviations, open questions. The programmer passes the report to Opus for review.

If the spec is silent or ambiguous, stop and ask; never choose.

## Roles

| Model | Job |
|---|---|
| Opus | Interviews the programmer, writes the architecture, ADRs and per-feature build specs in full detail |
| Sonnet | Writes code strictly from the build specs; does not make design decisions. If a spec is silent or ambiguous, stop and ask instead of choosing |

## Resume here

1. Read `docs/planning/context.md` top to bottom. It holds the vision, every decision so far, the
   open questions and the exact next step.
2. Read `docs/engineering-guidelines.md`. It applies to all work in this repository.
3. Continue at the "Next step" section of `docs/planning/context.md`.

## Working with the programmer

- The programmer has ADHD and prefers "ADHD mode" responses: lead with the next action, numbered
  steps, at most 5 items per list, restate where we are each turn, concrete time estimates, no
  preamble, no recap, no closing pleasantries. Turned off only by "stop adhd mode" or "normal mode".
- Interview in rounds of at most 5 questions, recommended option first, answerable with letters.
- Save every decision and answer to `docs/planning/context.md` in the same turn it is made.
