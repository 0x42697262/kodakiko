# 04 — Accounts and auth (core flows + server)

Status: draft 1 (2026-11-11).

Goal: everything needed to create an account, sign in, unlock with a fresh key bundle, change the
password, recover with the 24 words, sign out, and request or cancel account deletion — as pure
client-side flows in `packages/core` (used by the app in spec 07b) and as server routes, services and
repositories. Plus sessions, invites (redeem + CLI creation for bootstrap), the session-expiry job,
and the RISK-1 Node baseline. No UI (spec 07b).

Read first: `docs/design/crypto.md` §§3-5, 9; `docs/design/api.md` §§1-4;
`docs/design/data-model.md` §§2.1-2.4; `docs/design/security.md` §§1, 4, 6; spec 03 (conventions).
Depends on specs 02 and 03.

## 1. Contract (`packages/contract/src/`)

| File | Content |
|---|---|
| `limits.ts` (extend) | `USERNAME_PATTERN = '^[a-z0-9._-]{3,32}$'`, `DEVICE_LABEL_MAX_CHARS = 64`, `SESSION_TOKEN_PREFIX = 'kdk_s_'` (`SESSION_TOKEN_PATTERN` / `SESSION_TOKEN_REGEX` already exist from spec 03 §2.8a; `authenticate` uses that regex for the shape check), `INVITE_TOKEN_PREFIX = 'kdk_i_'`, `TOKEN_RANDOM_BYTES = 32` |
| `auth.ts` | Request/response schemas of `api.md` §3 (`paramsRequest`, `paramsResponse`, `signUpRequest`, `signInRequest`, `recoveryStartRequest`, `recoveryStartResponse`, `recoveryCompleteRequest`, `passwordChangeRequest`, `sessionDto`, `userDto`, `keyBundleDto`, `signUpResponse`, `signInResponse`, `sessionResponse`). Binary fields use `bytesSchema`; ids `idSchema`; usernames: `signUpRequest.username` uses `z.string().regex(new RegExp(USERNAME_PATTERN))`, but `paramsRequest`, `signInRequest` and `recoveryStartRequest` use `z.string().min(1).max(USERNAME_INPUT_MAX)` (`USERNAME_INPUT_MAX = 128`) so a malformed name gets the same fake answer as an unknown one instead of a revealing `422`; `inviteToken` matches `^kdk_i_[A-Za-z0-9_-]{43}$`; `opsLimit` int ≥ 1; `memLimitBytes` int ≥ 1 (floors are checked by the server's domain rule, not the schema, so the error is `KDF_PARAMS_TOO_WEAK` rather than a generic validation error) |
| `users.ts` | `userDto` re-export, `deletionRequestResponse = { deletionDueAt }` |
| `routes.ts` (extend) | `authParams`, `authSignUp`, `authSignIn`, `authKeyBundle`, `authRecoveryStart`, `authRecoveryComplete`, `authPassword`, `authSignOut`, `authSession`, `usersMe`, `usersMeDelete`, `usersMeDeleteCancel` |

`UserDto.status` uses `USER_STATUSES = ['active', 'pending_deletion'] as const`;
`USER_ROLES = ['admin', 'member'] as const` (both exported from `limits.ts`'s sibling `enums.ts`).

## 2. Core: account flows (`packages/core/src/account/`)

Pure; every function takes the `Sodium` port. Byte fields are `Uint8Array`; conversion to the
contract's base64url happens in the client services (spec 07b), not here and not in the HTTP
adapter.

### 2.1 `constants.ts`

`PASSWORD_MIN_CODE_POINTS = 12`, `ARGON2ID_BUDGET_MS = 8000`, `KEK_BYTES = 32`.

### 2.2 `username.ts`

`normalizeUsername(input: string): string` = NFKC → trim → `toLowerCase()`.
`isValidUsername(normalized: string): boolean` = matches `USERNAME_PATTERN` from contract.
The server imports both (architecture.md §3 allows server → core for shared rules).

### 2.3 `password-policy.ts`

```ts
export type PasswordRule = 'tooShort' | 'equalsUsername' | 'singleRepeatedCharacter';
export function checkPasswordPolicy(password: string, normalizedUsername: string): readonly PasswordRule[];
```

Length counted in Unicode code points of the NFKC form (`[...password.normalize('NFKC')].length`).
`equalsUsername` compares `normalizeUsername(password)` with the username. Empty result = allowed.
The UI maps rule keys to text (spec 07b).

### 2.4 `kdf-params.ts`

```ts
export type KdfParams = { readonly salt: Uint8Array; readonly opsLimit: number; readonly memLimitBytes: number };
export function startKdfParams(salt: Uint8Array): KdfParams;
export function nextKdfParamsAfterFailure(params: KdfParams): KdfParams | null;
export function meetsKdfFloor(params: Pick<KdfParams, 'opsLimit' | 'memLimitBytes'>): boolean;
```

- `startKdfParams`: `ARGON2ID_START_MEM_BYTES` (256 MiB), `ARGON2ID_START_OPS` (16).
- `nextKdfParamsAfterFailure`: halve memory, double ops; `null` when memory would drop below
  `ARGON2ID_MIN_MEM_BYTES` (128 MiB).
- `meetsKdfFloor`: `memLimitBytes >= ARGON2ID_MIN_MEM_BYTES && memLimitBytes * opsLimit >=
  ARGON2ID_MIN_WORK`. The salt length check is separate (`salt.length === SALT_BYTES`).

### 2.5 `derive.ts`

```ts
export type LoginMaterial = { readonly kek: Uint8Array; readonly loginKey: Uint8Array };
export async function deriveLoginMaterial(sodium: Sodium, password: string, params: KdfParams): Promise<LoginMaterial>;
export async function deriveWithAdaptiveParams(sodium: Sodium, password: string, salt: Uint8Array, startParams: (salt: Uint8Array) => KdfParams = startKdfParams): Promise<{ readonly params: KdfParams; readonly material: LoginMaterial }>;
export function recoveryLoginKey(sodium: Sodium, recoveryKey: Uint8Array): Uint8Array;
```

- `deriveLoginMaterial`: `kek = argon2id({ password: passwordToBytes(password), salt, opsLimit,
  memLimitBytes, outputLength: KEK_BYTES })`; `loginKey = kdfDerive(32, KDF_SUBKEY_IDS.login,
  KDF_CONTEXTS.login, kek)`.
- `deriveWithAdaptiveParams`: start params; on any thrown error from `argon2id` move to
  `nextKdfParamsAfterFailure`; when it returns `null` throw `KdfUnavailableError` (new class in
  `account/errors.ts`).
- `recoveryLoginKey` = `kdfDerive(32, KDF_SUBKEY_IDS.recoveryLogin, KDF_CONTEXTS.recovery, rk)`.

### 2.6 `sign-up.ts`

```ts
export type SignUpInput = { readonly username: string; readonly password: string; readonly homeCollectionName: string; readonly createdAtUtc: string; readonly randomId: () => string };
export type SignUpMaterial = {
  readonly request: SignUpRequestBytes;
  readonly secrets: AccountSecrets;
  readonly recoveryWords: readonly string[];
};
export type AccountSecrets = { readonly userId: string; readonly masterKey: Uint8Array; readonly privateKey: Uint8Array; readonly publicKey: Uint8Array; readonly homeCollectionId: string; readonly homeCollectionKey: Uint8Array };
export async function createSignUpMaterial(sodium: Sodium, input: SignUpInput, startParams: (salt: Uint8Array) => KdfParams = startKdfParams): Promise<SignUpMaterial>;
```

`SignUpRequestBytes` mirrors `signUpRequest` with `Uint8Array` for binary fields and without
`inviteToken`/`deviceLabel` (the caller adds them). Steps, exactly `crypto.md` §5.1 2-8:

1. `salt = randomBytes(SALT_BYTES)`; `deriveWithAdaptiveParams`.
2. `masterKey`, `recoveryKey`, `homeCollectionKey` = `randomBytes(KEY_BYTES)`; keypair =
   `boxKeypair()`; `userId = randomId()`, `homeCollectionId = randomId()`.
3. Wraps (`wrapKey`): `masterKeyByKek` (kek, ctx `masterKeyByKek`, binding userId),
   `masterKeyByRecovery` (recoveryKey, `masterKeyByRecovery`, userId), `recoveryKeyByMaster`
   (masterKey, `recoveryKeyByMaster`, userId), `privateKeyByMaster` (masterKey,
   `privateKeyByMaster`, userId) — `privateKey` is 32 bytes for X25519 so `wrapKey` applies —,
   `homeCollection.keyByOwner` (masterKey, `collectionKeyByMaster`, homeCollectionId).
4. `homeCollection.metadataBlob` = `sealJson(homeCollectionKey, { schemaVersion: 1, name:
   homeCollectionName, createdAtUtc }, collectionMetadata, homeCollectionId)` validated against
   `CollectionMetadataV1` before sealing.
5. `recoveryLoginKey`; `recoveryWords = encodeRecoveryKey(recoveryKey)`.
6. `wipe(kek)`, `wipe(recoveryKey)` before returning (the words carry it).

### 2.7 `sign-in.ts`, `key-bundle.ts`

```ts
export async function deriveForSignIn(sodium: Sodium, password: string, params: KdfParams): Promise<LoginMaterial>;
export function openKeyBundle(sodium: Sodium, kek: Uint8Array, userId: string, bundle: KeyBundleBytes): AccountKeys;
export type AccountKeys = { readonly masterKey: Uint8Array; readonly privateKey: Uint8Array; readonly publicKey: Uint8Array };
```

- `deriveForSignIn` first checks `meetsKdfFloor` and the salt length; failure throws
  `HostileServerParamsError` (new class) **before** running Argon2id (`crypto.md` §5.2 step 2).
- `openKeyBundle` unwraps `masterKeyByKek`, then `privateKeyByMaster`; a `DecryptionError` on the
  first unwrap is rethrown as `WrongPasswordError` (new class).

### 2.8 `password-change.ts`, `recovery.ts`

```ts
export async function createPasswordChangeMaterial(sodium: Sodium, input: { readonly userId: string; readonly currentPassword: string; readonly newPassword: string; readonly currentParams: KdfParams; readonly cachedMasterKeyByKek: Uint8Array }): Promise<PasswordChangeBytes>;
export function openRecovery(sodium: Sodium, words: readonly string[], userId: string, masterKeyByRecovery: Uint8Array): { readonly masterKey: Uint8Array; readonly recoveryKey: Uint8Array };
export async function createRecoveryCompletion(sodium: Sodium, input: { readonly userId: string; readonly newPassword: string; readonly masterKey: Uint8Array; readonly recoveryKey: Uint8Array }): Promise<RecoveryCompleteBytes>;
```

- Password change (`crypto.md` §5.4): derive current material; unwrap `cachedMasterKeyByKek`
  (failure → `WrongPasswordError`, no request); new salt + adaptive params; new kek, loginKey,
  `masterKeyByKek`; returns `{ currentLoginKey, salt, opsLimit, memLimitBytes, loginKey,
  masterKeyByKek }`.
- Recovery (`crypto.md` §5.5): `decodeRecoveryWords`; unwrap `masterKeyByRecovery` with the
  recovery key (binding `userId`), failure → `WrongRecoveryWordsError`. The **client needs
  `userId`** for the binding: `POST /auth/recovery/start` therefore returns `{ userId,
  masterKeyByRecovery }`; unknown usernames get a deterministic fake `userId` too (first 16 bytes
  of `HMAC(secret, "recovery-fake-id" || username)`, base64url), already specified in `api.md` §3
  and `crypto.md` §5.5.
- `createRecoveryCompletion` returns `{ recoveryLoginKey, salt, opsLimit, memLimitBytes, loginKey,
  masterKeyByKek }`.

### 2.9 Core tests (`packages/core/test/account.test.ts`)

Use `createNodeSodium()`. To keep tests fast, tests pass `startParams = (salt) => ({ salt,
opsLimit: 2, memLimitBytes: 8_388_608 })` to `deriveWithAdaptiveParams`/`createSignUpMaterial`;
every other function takes params from its caller. There is no floor override; floor checks are
tested on `meetsKdfFloor` directly. `createPasswordChangeMaterial` and `createRecoveryCompletion`
take the same optional `startParams` as their last argument.

Cases: sign-up material opens with `openKeyBundle` using a re-derived kek; recovery words open
`masterKeyByRecovery`; password change with a wrong current password → `WrongPasswordError` and no
output; adaptive params: a fake sodium whose `argon2id` throws for `memLimitBytes > 128 MiB` ends
at `{ 128 MiB, ops 32 }`; one that always throws → `KdfUnavailableError`; `deriveForSignIn` with
`memLimitBytes = 64 MiB` → `HostileServerParamsError` and the fake's `argon2id` was never called;
password policy table (11 chars → tooShort; 12 → ok; username equal → equalsUsername;
`"aaaaaaaaaaaa"` → singleRepeatedCharacter; combining characters count after NFKC).

`packages/core/scripts/measure-kdf.ts`: runs `deriveWithAdaptiveParams` with real start params 3
times in Node and prints min/median/max ms and the final params (**RISK-1 Node baseline**; report
the output in the spec's checklist; spec 07a measures phone and browser).

## 3. Server

### 3.1 Settings additions (`config/settings.ts`)

| Variable | Type | Default | Path |
|---|---|---|---|
| `FAKE_PARAMS_SECRET` | base64url string decoding to ≥ 32 bytes | required | `auth.fakeParamsSecret` (bytes) |
| `SESSION_IDLE_DAYS` | int 1-365 | 30 | `auth.sessionIdleDays` |
| `MAX_FAILED_SIGN_INS` | int 1-100 | 10 | `auth.maxFailedSignIns` |
| `FAILED_SIGN_IN_WINDOW_MINUTES` | int 1-1440 | 60 | `auth.failedSignInWindowMinutes` |
| `INVITE_DEFAULT_EXPIRY_DAYS` | int 1-90 | 7 | `auth.inviteDefaultExpiryDays` |
| `ACCOUNT_DELETION_GRACE_DAYS` | int 1-90 | 7 | `accounts.deletionGraceDays` |
| `JOB_EXPIRE_SESSIONS_INTERVAL_MINUTES` | int ≥ 1 | 1440 | `jobs.expireSessionsIntervalMs` (minutes × 60,000) |
| `MAX_COLLECTION_METADATA_BYTES` | int ≥ 256 | 4096 | `limits.maxCollectionMetadataBytes` (decoded bytes; checked for the home collection at sign-up and by collection PATCH in spec 06) |

`.env.example` gets the new keys; `FAKE_PARAMS_SECRET` documented as "generate with
`node -e \"console.log(require('node:crypto').randomBytes(32).toString('base64url'))\"`".

Constants: `WRAPPED_KEY_BYTES = 73`, `FAILED_SIGN_IN_DELAY_CAP_MS = 30_000`, `FAILED_SIGN_IN_BASE_DELAY_MS = 1000`,
`JOB_LOCK_IDS.expireSessions = 1001`, `HMAC_LABELS` = frozen
`{ paramsSalt: 'params-salt-1', recoveryFake1: 'recovery-fake-1', recoveryFake2: 'recovery-fake-2',
recoveryFake3: 'recovery-fake-3', recoveryFakeId: 'recovery-fake-id' }`.

### 3.2 Domain (`domain/auth-rules.ts`, pure)

```ts
export function kdfParamsAcceptable(params: { saltLength: number; opsLimit: number; memLimitBytes: number }): boolean;
export function signUpAllowed(mode: SignupMode, invite: InviteState | null, now: Date): SignUpDecision;
export function sessionExpiry(now: Date, idleDays: number): Date;
export function shouldTouchSession(lastUsedAt: Date, now: Date): boolean;
export function failedSignInDelayMs(failuresInWindow: number, maxFailures: number): number;
export function routeAllowedWhilePendingDeletion(routeId: string): boolean;
```

- `kdfParamsAcceptable` reuses `meetsKdfFloor` from core plus `saltLength === SALT_BYTES`.
- `signUpAllowed`: `invite` mode needs an invite that exists, is not redeemed, not revoked, not
  expired → `{ allowed: true, role: invite.role }`, else `{ allowed: false }`; `open` mode → allowed
  with role `member` and ignores the token.
- `failedSignInDelayMs`: 0 below `maxFailures`; then `min(cap, base × 2^(failures − maxFailures))`.
- `routeAllowedWhilePendingDeletion`: true exactly for the route ids `authSession`, `authSignOut`,
  `usersMe` and `usersMeDeleteCancel` (sign-in, params and recovery are unauthenticated and not
  affected). Every other authenticated route answers `403 ACCOUNT_PENDING_DELETION`.

### 3.3 Repositories (`adapters/db/`)

`users-repository.ts`, `sessions-repository.ts`, `invites-repository.ts`,
`collections-repository.ts` (only `insertHome` here). Each is a factory taking `Kysely<Database>`
(or a transaction) and returning plain functions; they translate rows ↔ domain types and never
decide. Methods:

| Repository | Methods |
|---|---|
| users | `insert(user)`, `findByUsername(username)`, `findById(id)`, `updateLoginMaterial(id, { kdfSalt, kdfOpsLimit, kdfMemLimitBytes, loginKeyHash, masterKeyByKek, updatedAt })`, `setStatus(id, status, deletionDueAt, updatedAt)` |
| sessions | `insert(session)`, `findByTokenHash(hash)` (joined with user `status`, `role`), `touch(id, lastUsedAt, expiresAt)`, `deleteById(id)`, `deleteAllForUser(userId, exceptSessionId?)`, `deleteExpired(now, limit)` returns count |
| invites | `insert(invite)`, `findByTokenHashForUpdate(hash)` (`SELECT … FOR UPDATE`), `markRedeemed(id, userId, now)` |
| collections | `insertHome({ id, ownerId, keyByOwner, metadataBlob, now })` (sets `created_at`, `updated_at` and `meta_updated_at` to `now`) |

### 3.4 Services (`services/`)

`createAuthService(deps: { db, settings, clock, failedSignIns })` returns:

| Method | Steps (in this order, guidelines 3.2) |
|---|---|
| `params(username)` | normalize + validate username (invalid → still fake params, no error, so the endpoint never reveals the rule boundary); user found → stored params; else fake: salt = first 16 bytes of `HMAC-SHA-256(secret, paramsSalt ‖ username)`, start params |
| `signUp(request, deviceLabel)` | 1 username equals `normalizeUsername(username)` and valid, else `VALIDATION_FAILED`; 2 decoded byte lengths, else `VALIDATION_FAILED`: `loginKey`, `recoveryLoginKey`, `publicKey` = 32; `salt` = 16; `masterKeyByKek`, `masterKeyByRecovery`, `recoveryKeyByMaster`, `privateKeyByMaster`, `homeCollection.keyByOwner` = 73 (`WRAPPED_KEY_BYTES` = 1 + 24 + 32 + 16); `homeCollection.metadataBlob` ≤ `maxCollectionMetadataBytes`; 3 `kdfParamsAcceptable`, else `KDF_PARAMS_TOO_WEAK`; 4 transaction: invite `FOR UPDATE` + `signUpAllowed` (else `INVITE_INVALID`), insert user (unique violation on username → `USERNAME_TAKEN`, on id → `ID_CONFLICT`), insert home collection, mark invite redeemed, insert session; 5 log `auth.signed_up` with userId and role |
| `signIn(username, loginKey, deviceLabel)` | 1 failure delay: `await sleep(failedSignInDelayMs(...))` (a delay > 0 logs `auth.throttled` with the hashed username prefix); 2 load user; compare `sha256(loginKey)` with stored hash via `timingSafeEqual` — for unknown users compare against a fixed dummy hash so timing is similar; 3 mismatch → record failure, log `auth.sign_in_failed` (username hashed with SHA-256 hex prefix 12, never raw), `INVALID_CREDENTIALS`; 4 success → clear failures, insert session, return session + user + key bundle |
| `keyBundle(userId)` | load user → `KeyBundleDto` |
| `recoveryStart(username)` | known → `{ userId, masterKeyByRecovery }`; unknown → deterministic fakes (`crypto.md` §5.5, §2.8 above) |
| `recoveryComplete(request, deviceLabel)` | failure delay as sign-in; verify `sha256(recoveryLoginKey)`; check new params floor; transaction: update login material, delete all sessions, insert new session; log `auth.recovered` |
| `changePassword(userId, sessionId, request)` | verify `currentLoginKey`; floor; transaction: update login material, delete all other sessions, delete current session, insert a new one (fresh token); log `auth.password_changed` |
| `signOut(sessionId)` | delete session |
| `authenticate(authorizationHeader, now)` | parse `Bearer kdk_s_…` (wrong shape → `UNAUTHENTICATED`); hash; find; none → `UNAUTHENTICATED`; `expiresAt <= now` → delete it, `SESSION_EXPIRED`; touch when `shouldTouchSession`; return `{ userId, sessionId, role, status }` |
| `requestDeletion(userId)` | status → `pending_deletion`, `deletionDueAt = now + grace`, delete **all** sessions; log `account.deletion_requested` |
| `cancelDeletion(userId)` | only when pending and `now < deletionDueAt` (else `FORBIDDEN`); status → `active`; log `account.deletion_cancelled` |
| `createInvite(role, expiresInDays, createdBy)` | token = `kdk_i_` + base64url of 32 random bytes (43 characters); store SHA-256 of the **UTF-8 bytes of the full token string** (prefix included; the same rule for session tokens); return `{ inviteUrl: ${publicBaseUrl}/invite#${token}, expiresAt }` |

`failedSignIns`: `adapters/memory/failed-sign-in-tracker.ts`, a `Map<usernameHashHex, { windowStart, count }>`
with window expiry; bounded to `FAILED_SIGN_IN_TRACKER_MAX_ENTRIES = 10_000` (oldest evicted). It is
per process, like the rate limiter (documented limitation, `deployment.md` §7).

Hashing helpers in `platform/hash.ts`: `sha256(bytes)`, `hmacSha256(key, label, message)`
(`label` and `message` UTF-8, concatenated), `constantTimeEqual(a, b)`.

### 3.5 HTTP

- `http/auth-plugin.ts`: decorates `request.auth`; a `preHandler` named `requireSession` that calls
  `authenticate` and enforces `routeAllowedWhilePendingDeletion` (`403
  ACCOUNT_PENDING_DELETION`). Each route declares `config: { routeId, auth: 'session' | 'none' }`.
- Routes: every row of `api.md` §3 plus `GET /users/me`, `POST /users/me/delete`,
  `POST /users/me/delete/cancel`, each bound to its contract schema and rate-limit group
  (`authSignIn` for sign-in, recovery start and recovery complete; `authSignUp` for sign-up, plus, when
  `SIGNUP_MODE=open`, `authSignUpOpen` (`{ max: 5, timeWindowMs: 86_400_000 }` per IP, security.md §4
  item 2) checked inside the sign-up service with `fastify.createRateLimit`; `auth`
  for the rest; `users` for `/users/*`).
- Controllers: decode base64url fields with `decodeBytes`, call one service method, encode the
  response. `deviceLabel` from the body.

### 3.6 Job and CLI

- `jobs/expire-sessions.ts`: deletes up to `JOB_BATCH_SIZE` expired sessions per run;
  `remaining = deleted === JOB_BATCH_SIZE`. Registered in `main.ts`.
- CLI command `create-invite --role <admin|member> [--expires-days <n>]`: builds settings + db the
  same way as `main.ts`, calls `createInvite` with `createdBy = null`, prints the URL on stdout
  (only the URL), exit 0. Invalid flags → usage on stderr, exit 2.

## 4. Tests

Server unit: every `auth-rules` function (table-driven, fixed dates).

Server integration (real Postgres, `app.inject`):

1. Bootstrap. Test material is built with `createSignUpMaterial` and the fast test
   `startParams` (8 MiB, ops 2). The server never re-runs Argon2id; it only checks the declared
   numbers. So: (a) the material sent as built → `422 KDF_PARAMS_TOO_WEAK`; (b) the same material
   with the request's `opsLimit` and `memLimitBytes` replaced by the floor (`32`, `134217728`) →
   `201`, role admin, home collection row exists, invite redeemed. Every test account in this file
   is created the same way through a helper `createTestAccount(app, { inviteToken, username })`,
   which keeps the material (login key, KEK, keys) for later assertions. Set `testTimeout` 30 s
   and `hookTimeout` 60 s in the integration vitest project.
2. Reusing the invite → `403 INVITE_INVALID`; expired invite → same; `SIGNUP_MODE=open` without
   token → `201` member.
3. Duplicate username → `409 USERNAME_TAKEN`; duplicate userId → `409 ID_CONFLICT`; username
   `"Alice"` (not normalized) → `422 VALIDATION_FAILED`.
4. `/auth/params` for a known user returns stored params; for an unknown user returns the same
   fake salt on two calls and different salts for two different unknown names; response shapes
   identical.
5. Sign-in success returns a key bundle that opens with core `openKeyBundle`; wrong key → `401
   INVALID_CREDENTIALS`; unknown user → same status and body shape.
6. After `MAX_FAILED_SIGN_INS` failures the next attempt takes ≥ `FAILED_SIGN_IN_BASE_DELAY_MS`
   (fake timers or a test setting of 1 for max failures).
7. Session: missing header → `401 UNAUTHENTICATED`; garbage → same; expired (fixed clock moved
   forward) → `401 SESSION_EXPIRED` and the row is gone; touch updates `last_used_at` at most every
   5 minutes.
8. Password change revokes the other session (its next call → 401), returns a new token; old
   password no longer signs in; new one does.
9. Recovery: start returns `{ userId, masterKeyByRecovery }` that opens with the words; unknown
   username returns identical bytes on two calls; complete with the right recovery key → new
   session, all old sessions revoked; wrong recovery login key → `401 INVALID_CREDENTIALS`.
10. Deletion: request → all sessions revoked; sign-in still works; `GET /api/v1/auth/key-bundle`
    with the new session → `403 ACCOUNT_PENDING_DELETION`; `GET /users/me` works; cancel →
    `active`; cancel after the due date (clock moved) → `403 FORBIDDEN`.
11. Logs: capture the logger stream during tests 5 and 8; assert no base64url of any `loginKey`,
    token or wrap appears.
12. `expire-sessions` job deletes only expired rows.
13. Sign-out → the token gets `401` afterwards.
14. `/auth/params` with `"Not A Valid Name!"` → `200` with fake params (no `422`).
15. Password change with a wrong `currentLoginKey` → `401 INVALID_CREDENTIALS`, nothing changed.

## 5. Steps

1. Contract additions + tests. 2. Core account module + tests + `measure-kdf.ts` run.
3. Server settings, hash helpers, domain rules + unit tests. 4. Repositories, services, auth
plugin, routes. 5. Job + CLI. 6. Integration tests. 7. `pnpm verify` + integration suite.

## 6. Verification checklist

- [ ] All unit and integration suites green (paste summaries).
- [ ] `measure-kdf.ts` output pasted (min/median/max ms, final params) — RISK-1 Node baseline.
- [ ] Manual bootstrap on the built server: `node dist/cli.js create-invite --role admin` prints a
      URL of the form `https://…/invite#kdk_i_…`; the token works once.
- [ ] `grep -rn "process.env" apps/server/src` → only `main.ts` and `cli.ts`.
- [ ] Database after the suite: `users.login_key_hash` holds 32-byte hashes; no column contains a
      raw login key (spot-check one row against the test's known key).

## 7. Done when

Checklist fully reported; suites green.
