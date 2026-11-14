# Feature specs for Sonnet

Each file is a complete build task. Rules for the implementing model (Sonnet):

1. Implement exactly what the spec says. The spec dictates files, names, signatures and values.
   If something is missing or ambiguous, **stop and ask**; do not choose.
2. Read `docs/engineering-guidelines.md` sections 1-5 before starting any spec; section 15 before
   reporting done.
3. Normative background: `docs/design/*.md`, `docs/architecture.md`, `docs/dependencies.md`
   (never add a dependency not on the allowlist), `docs/planning/context.md` (decision ids D-NN).
4. No explanatory comments in code (guidelines 4.1). No magic values (4.2): every constant goes to
   the named module the spec specifies.
5. Every spec ends with a verification checklist; run it and report results faithfully. Mark
   anything not run "UNVERIFIED".
6. One commit per spec when asked, message `feat(NN): <spec title>`.
7. Core exports: every spec that adds a module area to `packages/core/src/` (e.g. `media/`,
   `index/`, `domain/`, `sync/`, `account/`) adds an `index.ts` barrel for that area and one line
   `export * from './<area>/index.ts';` to `packages/core/src/index.ts`. Consumers import only from
   `@kodakiko/core` (deep imports are blocked by the package `exports`). Spec 02's "no additional
   exports" rule refers to names inside `crypto/`.
8. **Test first (D-72).** For each spec: (a) write the tests named in the spec's Tests section and the
   automated tests of its behaviors in `behaviors.md`, including the UI flows of spec 07c §8 whose
   last column names this spec (from 07b on); (b) run them and record in the report that
   they fail, and why (missing module or wrong result, not a broken test setup); (c) implement until
   they pass; (d) refactor with the tests green. A behavior marked `manual` in `behaviors.md` is
   checked by hand and reported. The spec is done only when every behavior row that names it passes;
   a `ui` flow counts only for the spec in the last column of 07c §8, and an `e2e` row only for the
   **last** spec it needs (the `tests/e2e` package exists from spec 08; client services from 07b on);
   other specs named in the same row are done when their own layers pass.
9. **Tests are fixed by the plan (D-76).** Tests named in a spec, in `behaviors.md` or in 07c §8 may
   never be deleted, skipped (`.skip`, `.todo`, `test.fixme`, commented out, excluded by config),
   loosened (weaker assertions, longer timeouts than the spec states, broader matchers) or changed
   to fit the code. If one looks wrong or cannot pass, stop and ask. Extra tests are allowed; list
   each one in the spec's report with one line on what it proves.
10. **`docs/` belongs to Opus.** Where a spec says to record something in `docs/` (a `facts.md`
   row, a measurement, a version), put the exact data in the report instead; Opus records it.
   Never edit `docs/`, `AGENTS.md` or `.pi/`.

## Phases (D-73)

| Phase | Specs | Result |
|---|---|---|
| 1 — local | 01-20, 20a, and spec 21 §1-§4 and §7a (images built locally with `docker-compose.build.yml`, the local compose stack, Caddy with DNS-01 for the programmer's domain pointing at the LAN/VPN address, operations guide, web manifest) | The app runs against a server on the programmer's own machine or network; the Android app picks its server at first run and can change it (Settings › Account) |
| 2 — online | spec 21 §5-§7 (CI, release workflow, registry images, release checklist, Renovate) and the public profile | Hosted or public deployments, signed release APKs |

Build order note: 07c is built **before** 07b (its letter is only a name), so every screen spec
can write its UI flows first. Phase 1 needs no online server: the domain is only used for certificates (DNS-01), its records
point at private addresses.

## Build order

| # | Spec | Depends on |
|---|---|---|
| 01 | `01-scaffolding.md` monorepo + tooling | — |
| 02 | `02-crypto-core.md` crypto in packages/core + vectors | 01 |
| 03 | `03-contract-server-skeleton.md` contract foundations + Fastify skeleton, config, migrations, jobs | 01 |
| 04 | `04-auth.md` account crypto flows in core + server auth, sessions, invites, deletion request | 02, 03 |
| 05 | `05-storage-server.md` S3 adapter, objects (presign/parts/confirm/download), quota, object GC jobs, `apply-bucket-cors` | 03, 04 |
| 06 | `06-entries-server.md` entries, media, memberships, sequence numbers, tombstones, diff + sync state, trash/erase/tombstone jobs | 04, 05 |
| 07a | `07a-client-foundation.md` Expo app shell, router, composition root, platform adapters (Sodium, RowStore, FileStore, KeyVault, SessionStore, Http), on-device vector check, RISK-1 measurement | 02, 04 |
| 07c | `07c-ui-test-harness.md` Playwright + Maestro harness, local UI test stack, element-id rule, media fixtures, flow catalog | 04, 05, 07a |
| 07b | `07b-auth-screens.md` local HTTPS dev stack, client auth service, welcome/sign-up/recovery-key/sign-in/lock/recover/cancel-deletion screens | 04, 05, 07a, 07c |
| 08 | `08-sync-client.md` sync decisions + merge in core, driver, uploads, Android native upload module, `tests/e2e` package | 06, 07a |
| 09 | `09-capture-import.md` camera, picker, paste, import pipeline, EXIF, temp files, device settings | 07b, 08 |
| 10 | `10-entries-client.md` entry create/edit/trash/restore/delete + outbox writes, tags | 08, 09 |
| 11 | `11-gallery.md` in-memory index, gallery grid, filters, search, multi-select | 10 |
| 12 | `12-calendar.md` calendar view + on-this-day | 11 |
| 13 | `13-entry-viewer.md` entry detail, markdown note (D-51), media pager, edit screen | 10, 11 |
| 14 | `14-thumbnails.md` thumbnail and original downloads, caches, pins, eviction, network rules | 08, 09, 11, 13 |
| 15 | `15-video-playback.md` web service worker range decrypt + Android temp-file playback | 05, 14 |
| 16 | `16-app-lock.md` lock/unlock lifecycle, biometrics, auto-lock, keep-me-unlocked (web) | 07b, 08-15 (its steps call their services) |
| 17 | `17-sharing.md` shared collections + shares, server and client (flag `sharing`) | 10-13, 16 |
| 18 | `18-public-links.md` link collections, stripped photo copies, viewer page, expiry job (flag `public_links`) | 15, 16, 17 |
| 19 | `19-admin.md` admin routes, screens, CLI commands, stats, account refresh | 04, 05, 07b, 16, 18 |
| 20 | `20-settings-export.md` settings screens, password change, recovery key display, account deletion, journal export (D-52, D-66) | 14, 16-19 |
| 20a | `20a-local-only-mode.md` Android local-only mode: on-device account, no sync, eviction off, connect a server later (D-83) | 08, 14, 16, 20 |
| 21 | `21-deployment.md` production images and compose, Caddy DNS-01 + CSP hashes, Garage setup + CORS, operations guide, CI and release workflows, release checklist | all |
| 22 | `22-material-ui.md` Material 3 UI modeled on Mihon: app bars, icon bottom navigation, More tab, tag tabs, Filter/Sort/Display sheet, declarative settings (D-84) | all client specs |
| 23 | `23-mihon-parity.md` v0.2.0: Mihon parity and power users: zoom-toolkit viewer, favorites, Filter/Sort/Display/Group sheet, More, tags editor, statistics, server and keys, data and storage, import and encrypted export | 22 |
