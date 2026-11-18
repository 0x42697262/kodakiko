# 11. Kotlin Android client on a Kotlin Multiplatform core; web client dropped

Date: 2026-11-14. Status: proposed (v0.4.0). Supersedes ADR 1 for the clients; ADR 1 still holds
for the server, `packages/contract` and `packages/core`.

## Context
v0.3.1 (Expo / React Native, one codebase for Android and web) works, but on a phone it is "not
fluid or smooth" (programmer, 2026-11-14). The v0.2.2 native-feel pass (ripples, Paper
components, haptics, transitions) did not close the gap. The UI target has been Mihon since D-84,
and Mihon is a Kotlin + Jetpack Compose app under Apache-2.0, so its code can be reused. Web use
is not a priority.

## Decision
- The Android client is rewritten in Kotlin with Jetpack Compose and Material 3, using Mihon's code
  and structure as the base (adapted files keep an attribution header) and PicDiary only where
  Mihon has nothing.
- Pure logic (crypto, contract types, account, domain, media parsing, index, sync decisions,
  export, markdown) moves into Kotlin Multiplatform modules (`androidTarget` + `jvm` now; `wasmJs`
  when web returns).
- The web client is dropped. The public link viewer is kept as a small standalone page (pending
  the programmer's answer).
- The server, `packages/contract` and `packages/core` stay in TypeScript. `packages/core` becomes
  the reference implementation that checks every byte the Kotlin app writes (differential tests).
- The app updates in place over v0.3.1 (same applicationId and signing key) and adopts the existing
  on-device files and database unchanged.

## Consequences
+ Native rendering, gestures, predictive back and media pipelines; direct reuse of ~15–20k lines of
  Mihon UI code.
+ Video plays through a decrypting Media3 data source with no plaintext file on disk.
+ Two implementations of the crypto and formats cross-check each other.
- Two languages in the repository; contract changes must be made in TS and Kotlin together
  (goldens and the reverse judge catch drift).
- No web app until a Kotlin web target exists.
- A large one-time port (~180 tasks), planned in `docs/planning/v0.4.0/` (local planning notes).
