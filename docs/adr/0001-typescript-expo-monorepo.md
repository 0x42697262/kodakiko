# 1. TypeScript everywhere: Expo + Fastify monorepo

Date: 2026-11-11. Status: superseded for the clients by [ADR 11](0011-kotlin-android-client.md)
(v0.4.0); still accepted for the server, `packages/contract` and `packages/core`. Decision: D-07.

Since v0.4.0 the Android app is a Kotlin app (`apps/android`) and there is no web app; the Expo
client described here (v0.1–v0.3) was removed in v0.4.1.

## Context
Android app and web app from one team of one. With E2EE the server is thin,
so code sharing between the two clients matters most (planning interview, stack discussion).

## Decision
pnpm monorepo: Expo (React Native + React Native Web, Expo Router) for both clients, Fastify +
Postgres server, `packages/core` (pure crypto/domain/sync) and `packages/contract` (Zod schemas)
shared by everything. Platform differences only in `*.native.ts` / `*.web.ts` adapter files.

## Consequences
+ One language, one contract, each rule written once; a widely known stack.
+ iOS later is mostly free.
- Large-file crypto on Android needs JSI (react-native-libsodium) and careful chunked file I/O;
  mitigated by the core stream encryptor/decryptor (ADR 4).
- Expo/RN version churn; pinned SDK, Renovate with release-age delay (ADR 10).
