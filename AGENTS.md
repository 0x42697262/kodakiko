# AGENTS.md — Kodakiko

Kodakiko is an end-to-end encrypted photo/video journal: quick capture (several photos/videos per
entry plus a markdown note), tags instead of categories, gallery browsing, multiple users, Android
app first and a web app usable on phones.

## Status

**Planning phase. No code yet.** Architecture docs are done (draft 2, reviewed once). Feature specs
live in `docs/features/`; specs are implemented strictly in the order of
`docs/features/README.md`, one at a time, only after the programmer says to start coding.
Sonnet may not add dependencies outside `docs/dependencies.md`.

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
