# 07b — Auth screens, client auth service, local development stack

Status: draft 1 (2026-11-11).

Goal: a user can choose a server (Android), sign up with an invite, see and confirm the recovery
key, sign in, unlock after a restart, recover with the 24 words, cancel a pending deletion, and
sign out — on Android and web, against a local HTTPS development stack.

Read first: spec 04 §2 (core flows), spec 07a (adapters, account state, conventions);
`docs/design/client.md` §§1, 6, 7.1; `docs/design/crypto.md` §§5, 9. Depends on 04, 05 (Garage bootstrap and `apply-bucket-cors` for the dev stack) and 07a.

## 1. Local development stack

`infra/compose/docker-compose.dev.yml`:

| Service | Image | Notes |
|---|---|---|
| `postgres-dev` | same pinned `postgres:17` | volume `kodakiko-dev-pg` |
| `garage-dev` | same pinned Garage | config `infra/compose/dev/garage.toml` (copy of the test config, persistent volume); bucket `kodakiko-dev` created by the same bootstrap steps as spec 05 §9 (script `infra/compose/dev/bootstrap-garage.sh`, idempotent); port `127.0.0.1:53901:3900` so the API on the host reaches it |
| `caddy-dev` | pinned `caddy:2` (no DNS plugin needed) | `infra/compose/dev/Caddyfile`; ports `9443:9443`, `9444:9444`. High ports because `adb reverse` on a non-rooted phone cannot bind ports below 1024 |

The API runs on the host (`pnpm --filter @kodakiko/server dev` = esbuild watch + `node --watch
dist/main.js`, with `apps/server/.env.dev` gitignored, `.env.dev.example` committed) and Expo web on
the host (`pnpm --filter @kodakiko/client start:web`).

`Caddyfile` (dev):

```
{
  local_certs
}
https://localhost:9443 {
  handle /api/* {
    reverse_proxy host.docker.internal:8080
  }
  handle /health/* {
    reverse_proxy host.docker.internal:8080
  }
  handle {
    reverse_proxy host.docker.internal:8081
  }
}
https://localhost:9444 {
  reverse_proxy garage-dev:3900
}
```

(`host.docker.internal` needs `extra_hosts: ["host.docker.internal:host-gateway"]` on Linux.)

Server `.env.dev.example`: `PUBLIC_BASE_URL=https://localhost:9443`,
`S3_ENDPOINT=http://127.0.0.1:53901`, `S3_PUBLIC_ENDPOINT=https://localhost:9444`,
`TRUST_PROXY_HOPS=1`, `REQUIRE_HTTPS=true`, `SIGNUP_MODE=invite`, a dev-only `FAKE_PARAMS_SECRET`.
Bucket CORS for the dev bucket (origin `https://localhost:9443`, rule of `deployment.md` §2.1) is
applied with the server CLI command `apply-bucket-cors` (spec 05 §8), which reads the origin from
`PUBLIC_BASE_URL`.

Android device or emulator: install Caddy's local root certificate (exported from the container,
`/data/caddy/pki/authorities/local/root.crt`) as a user CA, then `adb reverse tcp:9443 tcp:9443`
and `adb reverse tcp:9444 tcp:9444`, so `https://localhost:9443` on the device reaches the dev
machine. On Android the server address entered on `/welcome` is `https://localhost:9443`.
Steps go into `docs/development.md` (new; linked from `docs/README.md`).

Whether Caddy forwards the original `Host` header to Garage unchanged (required for presigned
URLs, `storage.md` §1) is checked here once: a presigned PUT through `https://localhost:9444`
succeeds. Record the result as `F-S3-07` in `docs/facts.md`.

## 1a. Server config service (`src/services/config/config-service.ts`)

```ts
export type ConfigService = {
  readonly get: () => PublicConfigDto | null;
  readonly refresh: () => Promise<PublicConfigDto | null>;
  readonly subscribe: (listener: (config: PublicConfigDto) => void) => () => void;
};
```

- `refresh()`: `GET /api/v1/config` (auth `none`, idempotent); on success validates, caches in memory
  and in the global store key `kodakiko.serverConfig` (JSON, the same store as
  `kodakiko.activeAccount`), notifies; on failure keeps the cached value.
- At start: load the stored value synchronously into memory (so offline starts have limits and
  flags), then `refresh()` in the background. On every `lifecycle.subscribeActive(true)` →
  `refresh()`. On Android, `ServerUrlStore.choose` already returns a config; store it the same way.
- Consumers: sign-up (`signupMode`), capture (`maxMediaPerEntry`, `maxUploadBytes`), trash
  (`trashDays`), sharing and public-link entry points (flags). When `get()` is `null` (first start
  offline), consumers use `CONFIG_FALLBACK` in client constants: `maxMediaPerEntry 20`,
  `maxUploadBytes 3758096384`, `trashDays 30`, `accountDeletionGraceDays 7`, flags `false`, `signupMode 'invite'`.

## 2. Client auth service (`src/services/auth-service.ts`)

`createAuthService(context)` returns:

```ts
export type AuthService = {
  readonly restore: () => Promise<void>;
  readonly chooseServer: (input: string) => Promise<ServerChoice>;
  readonly signUp: (input: { username: string; password: string; inviteToken: string | null; keepSignedIn: boolean }) => Promise<{ recoveryWords: readonly string[] }>;
  readonly confirmRecoveryKeySaved: () => Promise<void>;
  readonly signIn: (input: { username: string; password: string; keepSignedIn: boolean }) => Promise<void>;
  readonly unlockWithPassword: (password: string) => Promise<void>;
  readonly unlockWithDevice: () => Promise<boolean>;
  readonly recover: (input: { username: string; words: string; newPassword: string }) => Promise<void>;
  readonly pendingRecoveryWords: () => Promise<readonly string[] | null>;
  readonly cancelDeletion: () => Promise<void>;
  readonly signOut: () => Promise<void>;
};
```

Persistent data (all non-secret or ciphertext):

| Where | Key | Value |
|---|---|---|
| global (`expo-secure-store` native / `localStorage` web) | `kodakiko.activeAccount` | `accountKey` (`<host>/<userId>`) and username |
| RowStore `kv` | `account.userId`, `account.username`, `account.kdfParams` (JSON with base64url salt), `account.masterKeyByKek`, `account.recoveryKeyByMaster`, `account.publicKey`, `account.privateKeyByMaster`, `account.recoveryConfirmed` (JSON boolean; **a missing key means confirmed**), `account.homeCollectionId`, `account.deletionDueAt` (ISO string or absent; from `UserDto.deletionDueAt`, shown on `/cancel-deletion`) | from sign-up / sign-in / key bundle |
| SessionStore | per account | session token |
| KeyVault (Android) | per account | master key |

Flows (each step's failure maps to a text key, §4):

1. `restore()` at app start:
   1. Android without a server URL → state `noServer`.
   2. No active account or no session token → `signedOut` (username prefilled on sign-in).
   3. `GET /auth/session` (idempotent, timeout): `401` → clear token → `signedOut`; network error →
      continue **offline** (offline-first, D-06); `status = 'pending_deletion'` → `pendingDeletion`.
   4. Open RowStore and FileStore for the account → `locked`.
   5. Android: `unlockWithDevice()`; success → `unlocked`.
2. `unlockWithDevice()` (Android): `KeyVault.loadMasterKey(prompt)`; null → stay `locked` (the lock
   screen offers the password). With the master key: unwrap `privateKeyByMaster` from kv → state
   `unlocked`.
3. `unlockWithPassword(password)` (`crypto.md` §5.3): params and `masterKeyByKek` from kv →
   `deriveForSignIn` → `openKeyBundle`. `WrongPasswordError` → if online, fetch
   `GET /auth/key-bundle` once, store it, retry; second failure → `wrongPassword`. Android success
   also re-stores the master key in the KeyVault (re-enrolls after a biometric change).
4. `signUp`: policy check (core) → navigate to the `/securing` route and await
   `lifecycle.afterInteractions()` (07a §4.8) so its frame paints before the JS thread blocks →
   `createSignUpMaterial` with `homeCollectionName = TEXT.defaults.homeCollectionName` →
   `POST /auth/sign-up` with `deviceLabel` (§3) → save session, active account, kv data,
   `recoveryConfirmed=false`, KeyVault (Android) → returns the words (kept in memory only).
5. `confirmRecoveryKeySaved`: sets `recoveryConfirmed=true` → `unlocked`.
6. `pendingRecoveryWords`: if `recoveryConfirmed` is false and the account is unlocked, unwrap
   `recoveryKeyByMaster` with the master key and re-encode the words (a sign-up interrupted before
   confirmation shows the words again after the next unlock; `index.tsx` routes to
   `/recovery-key` in that case).
7. `signIn`: `POST /auth/params` → `deriveForSignIn` (`HostileServerParamsError` →
   `unsafeServer`) → `POST /auth/sign-in` → `openKeyBundle` (should not fail; if it does,
   `wrongPassword`) → save everything with `recoveryConfirmed=true` (this device never showed the
   words; the user confirmed them at sign-up elsewhere) → `unlocked` (or `pendingDeletion`).
8. `recover`: `normalizeRecoveryInput` → `POST /auth/recovery/start` → `openRecovery`
   (`WrongRecoveryWordsError`/`InvalidRecoveryWordsError` → `recoveryWordsInvalid` with the word
   position when known) → policy check on the new password → `createRecoveryCompletion` →
   `POST /auth/recovery/complete` (its response carries the key bundle) → save with
   `recoveryConfirmed=true` → `unlocked`.
9. `cancelDeletion`: `POST /users/me/delete/cancel` → `restore()`.
10. `signOut`: `POST /auth/sign-out` with a 5 s timeout, errors ignored → clear session token,
    `KeyVault.forget`, `RowStore.destroy`, `FileStore.destroy`, clear active account, wipe keys in
    memory → `signedOut`.

Keys in memory (`AccountKeys`) live only in the account-state store. Until spec 16 is built, every
transition away from `unlocked` calls `wipe` on them; spec 16 moves the wipe into its last lock
step `wipeKeys` (the state change itself then no longer wipes, so lock steps still running can
finish).

## 3. Device label

`src/adapters/device-label/device-label.native.ts` returns `"Android"`;
`device-label.web.ts` returns the first match of `Edg/`→`Edge`, `Firefox/`→`Firefox`,
`Chrome/`→`Chrome`, `Safari/`→`Safari` in `navigator.userAgent` (in that order), else
`"Browser"`. Patterns in `keys/`.

## 4. Screens

All screens use the 07a primitives; one primary button each; Enter submits on web; buttons show
`busy` during calls; errors appear in a `Banner` above the form. Copy (exact strings, put in
`text/en.ts`):

| Key | Text |
|---|---|
| `welcome.title` | `Kodakiko` |
| `welcome.subtitle` | `Your private journal of photos, videos and notes.` |
| `welcome.serverLabel` | `Invite link or server address` |
| `welcome.continue` | `Continue` |
| `welcome.signIn` / `welcome.signUp` (web) | `Sign In` / `I Have an Invite` |
| `signUp.title` | `Create Your Account` |
| `signUp.username` / `signUp.password` / `signUp.confirm` | `Username` / `Password` / `Confirm password` |
| `signUp.submit` | `Create Account` |
| `signUp.securing` | `Securing your account…` |
| `signUp.securingDetail` | `This takes a few seconds. Keep the app open.` |
| `password.tooShort` | `Use at least 12 characters.` |
| `password.equalsUsername` | `Don't use your username as your password.` |
| `password.singleRepeatedCharacter` | `Don't use one character repeated.` |
| `password.mismatch` | `Passwords don't match.` |
| `recoveryKey.title` | `Your Recovery Key` |
| `recoveryKey.body` | `Write these 24 words down and keep them somewhere safe. If you forget your password, they are the only way back into your journal. Nobody, including the server's owner, can recover your account without them.` |
| `recoveryKey.done` | `I've Written It Down` |
| `recoveryKey.confirmTitle` | `Confirm Your Recovery Key` |
| `recoveryKey.confirmWord` | `(position) => \`Word ${position}\`` |
| `recoveryKey.confirmSubmit` | `Confirm` |
| `recoveryKey.confirmWrong` | `That doesn't match. Check your written copy.` |
| `signIn.title` / `signIn.submit` | `Sign In` / `Sign In` |
| `signIn.keepSignedIn` (web) | `Keep me signed in on this browser` |
| `signIn.username` / `signIn.password` | `Username` / `Password` |
| `signUp.invite` | `Invite link` |
| `lock.password` | `Password` |
| `recover.username` / `recover.confirm` | `Username` / `Confirm new password` |
| `signIn.forgot` | `Forgot password?` |
| `lock.title` | `Journal Locked` |
| `lock.unlockDevice` | `Unlock` |
| `lock.unlockPassword` | `Unlock with Password` |
| `lock.wrongPassword` | `That password is incorrect.` |
| `lock.biometricPrompt` | `Unlock Kodakiko` |
| `recover.title` / `recover.words` / `recover.newPassword` / `recover.submit` | `Recover Your Account` / `Recovery key (24 words)` / `New password` / `Recover Account` |
| `cancelDeletion.title` | `Account Scheduled for Deletion` |
| `cancelDeletion.body` | `(date) => \`Your journal will be erased on ${date}. You can still keep it.\`` |
| `cancelDeletion.submit` / `cancelDeletion.signOut` | `Keep My Account` / `Sign Out` |
| `errors.wrongPassword` | `Username or password is incorrect.` |
| `errors.invalidCredentials` | `Username or password is incorrect.` |
| `errors.unreachable` | `Can't reach the server. Check your connection or VPN, then try again.` |
| `errors.notHttps` | `The server address must start with https://.` |
| `errors.notKodakiko` | `That address isn't a Kodakiko server.` |
| `errors.inviteInvalid` | `This invite link is invalid, used or expired. Ask for a new one.` |
| `errors.usernameTaken` | `That username is taken.` |
| `errors.usernameInvalid` | `Use 3–32 lowercase letters, digits, dots, dashes or underscores.` |
| `errors.unsafeServer` | `The server asked for unsafe security settings, so sign-in stopped. Contact the server's owner.` |
| `errors.kdfUnavailable` | `This device doesn't have enough memory to secure the account.` |
| `errors.recoveryWordsInvalid` | `(position: number \| null) => position === null ? 'These words don\\'t form a valid recovery key.' : \`Word ${position} isn't in the recovery word list.\`` |
| `errors.rateLimited` | `Too many attempts. Wait a minute and try again.` |
| `errors.generic` | `Something went wrong. Try again.` |
| `defaults.homeCollectionName` | `Journal` |

Screens (routes from `client.md` §1):

| Route | Content | Primary action |
|---|---|---|
| `/welcome` | Android: server field + Continue (→ `/sign-up` with invite token when the input was an invite link, else `/sign-in`). Web: title, subtitle, Sign In, I Have an Invite (→ `/sign-up`, asks for the invite link) | Continue |
| `/invite` (web) | reads `location.hash`, strips `#`, stores the token in memory, `router.replace('/sign-up')`; the token is never written to storage or logs | — |
| `/sign-up` | username, password, confirm, live policy hints below the password (each rule shown when it fails, after the field was touched); invite field only when no token is known and `signupMode = invite` | Create Account |
| `/securing` | spinner-free static text (`signUp.securing` + detail) | — |
| `/recovery-key` | 24 words in a 2-column numbered grid, selectable text; then confirm step asking 3 positions chosen with `randombytes_uniform`-equivalent (`sodium.randomBytes` → index) | I've Written It Down → Confirm |
| `/sign-in` | username (prefilled), password, keep-signed-in (web), Forgot password? | Sign In |
| `/lock` | Android: Unlock (device) and Unlock with Password; web: password field; a wrong password shows `lock.wrongPassword` under the field | Unlock |
| `/recover` | username, 24-word multi-line field, new password, confirm | Recover Account |
| `/cancel-deletion` | due date, Keep My Account, Sign Out | Keep My Account |

`/recovery-key` does not block screenshots (`FLAG_SECURE` was not requested, security.md §1.2
item 5). The words stay on screen only until "I've Written It Down" is pressed.

## 5. Tests

1. `auth-service.test.ts` (Node, fake Http, Sodium from `createWrappersSodium(lib, lib-argon2)` of
   spec 07a §4.1 with `libsodium-wrappers-sumo` loaded directly, KDF start params overridden through the service's `kdfStartParams` dependency, default
   `startKdfParams`): sign-up stores kv data and session, `recoveryConfirmed=false`; restore with a
   401 → `signedOut`; restore offline → `locked`; unlock with the right password → `unlocked`;
   wrong password → one `key-bundle` fetch then `wrongPassword`; stale cached wrap + fresh bundle
   → unlocked and cache replaced; hostile params → `unsafeServer` and no Argon2id call; recovery
   happy path; sign-out wipes keys and destroys stores (fakes record calls).
2. Manual on Android dev build and Chrome against the dev stack (§1), each step a checklist line.
3. UI flows U-01 to U-05 of spec 07c §8, written **first** (red), green at the end; plus
   `seedAccount` and `seedDeletionRequest` in `tests/ui/support/seed.ts`, and
   `apps/client/src/testing.ts` exporting `createTestAuthContext({ baseUrl, fetcher, sodium })`
   (the auth service of §2 with memory kv, session store and key vault; spec 08 extends this file
   with `createTestClientContext`).

## 6. Steps

1. Dev stack files + `docs/development.md`; bootstrap; `F-S3-07`.
2. Device label adapter; auth service + tests.
3. Text catalog entries; screens; route guards in `app/index.tsx`.
4. Manual runs (§7).

## 7. Verification checklist

- [ ] `pnpm verify` green; `auth-service.test.ts` cases all pass.
- [ ] `F-S3-07` recorded (presigned PUT through Caddy works or the fix needed).
- [ ] Android: invite link → sign-up → securing screen → recovery words → confirm → placeholder
      gallery; kill app → reopen → biometric unlock; Sign Out → welcome.
- [ ] Android: kill the app on the recovery-word screen → reopen → unlock → words shown again.
- [ ] Web: same flow with `/invite#…`; reload → `/lock` (password) → unlocked; with keep-signed-in
      off, closing the tab and reopening → `/sign-in`.
- [ ] Password change on device A (via a temporary dev button or a test script calling the API)
      → device B (web) unlock with the new password succeeds through the key-bundle refresh.
- [ ] Recovery with the 24 words on web → new password works; the old session on Android gets
      `/sign-in`.
- [ ] Deletion requested via API → app shows `/cancel-deletion`; Keep My Account → gallery.
- [ ] Offline (VPN/Wi-Fi off) cold start with a valid session → `/lock` → unlock works.
- [ ] Server logs during the runs contain no token, login key or wrap values (grep with the
      values from a debug session).
