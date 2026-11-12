# 1. TypeScript everywhere: Expo + Fastify monorepo

Date: 2026-11-11. Status: accepted. Decision: D-07.

## Context
Android app and web app from one team of one; Sonnet writes the code. With E2EE the server is thin,
so code sharing between the two clients matters most (planning/context.md, stack discussion).

## Decision
pnpm monorepo: Expo (React Native + React Native Web, Expo Router) for both clients, Fastify +
Postgres server, `packages/core` (pure crypto/domain/sync) and `packages/contract` (Zod schemas)
shared by everything. Platform differences only in `*.native.ts` / `*.web.ts` adapter files.

## Consequences
+ One language, one contract, each rule written once; Sonnet's strongest stack.
+ iOS later is mostly free.
- Large-file crypto on Android needs JSI (react-native-libsodium) and careful chunked file I/O;
  mitigated by the core stream encryptor/decryptor (ADR 4).
- Expo/RN version churn; pinned SDK, Renovate with release-age delay (ADR 10).
