# 20a — Local-only mode on Android (D-83)

Status: draft 1 (2026-11-14, Opus; settles OI-6).

Goal: on Android, a person can use Kodakiko **without a server**. The account keys are created on
the phone with the spec 04 crypto (password, recovery key, biometric unlock as in spec 16). Entries
live in the local index and outbox, and nothing syncs. Later the person can **connect a server**.
That registers the very same keys and ids there, and the normal sync path uploads everything. The
phone holds the only copy until then, so the app says so and keeps every original (no eviction).
Web has no local-only mode (browser storage can be evicted).

Read first: D-83 and OI-6 in `docs/planning/context.md`; specs 04 (sign-up material, recovery),
07a (account state, KeyVault, RowStore keys), 07b (welcome, sign-up, recovery screens, auth
service), 08 (sync service, outbox), 14 (eviction), 16 (lock and unlock steps), 17 (Shared tab),
18 (links), 19 (account refresh), 20 (Settings). Depends on 08, 14, 16, 20.

## 0. Decisions (OI-6 settled)

| OI-6 point | Decision |
|---|---|
| Local account → server account | **Reuse the device-created keys and ids.** Local setup runs `createSignUpMaterial` exactly as sign-up does and stores everything a sign-up request needs. Connecting rebuilds the sign-up request from the stored wraps plus the password (§2.2): same `userId`, home collection id and key, entry and media ids. Nothing is re-wrapped or re-encrypted, and no id changes. The outbox already holds every `createEntry`/upload item, so the normal sync path uploads the journal |
| Username in local mode | Chosen at setup with the server's rules (`isValidUsername`, 07b). It is shown on the lock screen and in Settings. It is **not** bound into any crypto (wraps bind `userId`), so the connect screen pre-fills it and lets the person change it when the server says it is taken |
| Hidden or disabled locally | Table in §4: sharing, public links, admin, quota and server rows, Change Server, Sync Now, and account deletion (replaced by "Erase This Journal…") |
| Eviction and sync gating | A persistent account mode (`account.mode` = `local` \| `server`, §1.2). In `local` mode the sync service never runs a pass, the unlock steps `startSync`/`refreshMe` are skipped, `enforceCap`/`clearOriginals` do nothing, and their Settings controls are hidden. Originals are never evicted because nothing is uploaded, and the eviction rules already skip `uploadState !== 'uploaded'` (spec 14), so the mode check is a second guard |
| Release APK path | Phase 1 (D-73) builds **debug** APKs only (D-80), and local mode works in them with no server at all. A signed release APK belongs to phase 2 (spec 21 §5-7) and needs nothing extra for local mode. Connecting a release build later needs a server with a publicly trusted certificate (spec 21's Let's Encrypt DNS-01), because release builds trust system CAs only (07b network security config) |

## 1. Account mode and storage

### 1.1 Account key and stores

- A local account's account key is `local/<userId>` (07a `RowStore`/`FileStore`/KeyVault names use
  the account key). It **stays** after connecting: the account key is only a local storage label,
  and `readActiveAccount()` reads it from the stored active-account record rather than recomputing
  it from the server host. A later sign-in on this device (after sign-out) creates host-keyed
  stores as usual.
- The active-account record (07b `kodakiko.activeAccount`) gains `mode: 'local' | 'server'`
  (schema version 2; version 1 records read as `server`).

### 1.2 Mode

- kv `account.mode` (`STORAGE_KEYS.accountMode`): `'local'` or `'server'`. A missing value means
  `server`.
- `AccountModeStore` (`src/services/account/account-mode.ts`): an observable `get(): 'local' |
  'server'`, loaded from the active-account record during `restore()` and at sign-in, sign-up and
  local setup; set to `server` after a successful connect.
- Local mode also stores kv `account.masterKeyByRecovery` (sign-up never kept it locally, because
  recovery goes through the server). It is needed for local recovery (§3.3) and for the connect
  request (§2.2).

## 2. Core additions (`packages/core/src/account/local.ts`)

### 2.1 Local setup material

`createSignUpMaterial` is reused unchanged (same KDF start parameters, adaptive floor and
recovery key).

### 2.2 Rebuilding the sign-up request

```ts
export type StoredLocalAccount = {
  readonly userId: string;
  readonly kdfParams: KdfParams;
  readonly masterKeyByKek: Uint8Array;
  readonly masterKeyByRecovery: Uint8Array;
  readonly recoveryKeyByMaster: Uint8Array;
  readonly publicKey: Uint8Array;
  readonly privateKeyByMaster: Uint8Array;
  readonly homeCollection: { readonly id: string; readonly keyByOwner: Uint8Array; readonly metadataBlob: Uint8Array };
};
export async function rebuildSignUpRequest(sodium: Sodium, input: { readonly password: string; readonly username: string; readonly stored: StoredLocalAccount }): Promise<SignUpRequestBytes>;
```

1. `deriveLoginMaterial(sodium, password, stored.kdfParams)` → `{ kek, loginKey }`.
2. Unwrap `masterKeyByKek` with `kek` (AD `masterKeyByKek`, binding `userId`); a failure throws
   `WrongPasswordError`.
3. Unwrap `recoveryKeyByMaster` with the master key; `recoveryLoginKey(sodium, recoveryKey)`.
4. Return the request with the stored wraps and ids **unchanged**, the given username, and the new
   `loginKey`/`recoveryLoginKey`. Wipe `kek`, the master key and the recovery key.

### 2.3 Local password change and recovery

- Password change: `createPasswordChangeMaterial` (spec 04) as in spec 20, but **no** HTTP call:
  store the new `kdfParams` and `masterKeyByKek`.
- Recovery: `openRecovery`-style local open: decode the 24 words → recovery key → unwrap
  `masterKeyByRecovery` → `createRecoveryCompletion` gives new KDF params and `masterKeyByKek`
  for the new password → store them. No HTTP call.

## 3. Client

### 3.1 Welcome and local setup

- `/welcome` (Android only) gets a second button "Use Without a Server" (`welcome.local`) below the
  server field. The web build never shows it (`platformTraits.localMode === false`).
- `/local-setup` (`LocalSetupScreen`): a warning card "This phone will hold the only copy of your
  journal until you connect a server. Export it from Settings to keep a backup." with a required
  checkbox "I understand" (`localSetup.understand`). Below it: username, password and confirm
  fields with the 07b hints, then "Create Journal" (`localSetup.create`).
- `authService.setUpLocal({ username, password })`: `createSignUpMaterial` (via `/securing` as in
  sign-up) → account key `local/<userId>` → `openStores` → `saveAccountData` (plus kv
  `account.masterKeyByRecovery`, the home collection row as in sign-up, `account.mode = 'local'`,
  `account.recoveryConfirmed = false`) → `keyVault.storeMasterKey` → active-account record with
  `mode: 'local'` → `unlockedState` → `/recovery-key` (07b confirm flow) → gallery. No HTTP call
  of any kind.

### 3.2 Restore

`restore()` reads the active-account record first. If it is `local`, the server URL is not needed:
the stores open, the state becomes `locked`, and `unlockWithDevice()` runs as usual. Local unlock
with password never refetches a key bundle (07b's `fetchFreshBundle` is skipped in local mode).

### 3.3 Settings and screens in local mode

| Place | Server mode | Local mode |
|---|---|---|
| Settings › Account | as spec 20 | username, "Stored only on this phone", **Connect a Server…**, Change Password… (local, §2.3), Show Recovery Key…, **Erase This Journal…** |
| Security code (spec 17/20) | shown | hidden |
| Change Server (spec 20) | shown | hidden |
| Delete Account (spec 20) | shown | replaced by "Erase This Journal…" (confirm with username + password → `authService.eraseLocal()`: sign-out steps, destroy stores, forget the KeyVault item and the active-account record → `/welcome`) |
| Settings › Storage | as spec 20 | usage only; cap picker, Clear Downloaded Originals and Wi-Fi rule hidden |
| Settings › Sync | as spec 20 | the text "This journal is stored only on this phone." and **Connect a Server…**; counts and buttons hidden |
| Settings › Public Links / Admin rows | per flags/role | hidden |
| Shared tab (spec 17) | as spec 17 | "Connect a server to share journals." only |
| Entry viewer "Share…" (spec 13/17/18) | per flags | hidden |
| Lock screen "Forgot password?" | `/recover` (server) | `/recover` in local mode (§2.3; no username field) |
| Sync banner (spec 08) | as spec 08 | never shown |

### 3.4 Gating (one place)

- `create-app-context.ts` builds the unlock steps with `when: () => mode.get() === 'server'` on
  `startSync` and `refreshMe`; `enforceCache` stays (it is a no-op without uploaded objects).
- `syncService.syncNow()`/`notifyLocalWrite()` return immediately when the mode is `local`, so
  entry edits only enqueue outbox rows.
- `originals.enforceCap()` and `clearOriginals()` return immediately in local mode.
- `config.refresh()` is not called in local mode (no server), so `CONFIG_FALLBACK` applies:
  `maxMediaPerEntry` 20 and `maxUploadBytes` 3.5 GiB keep the same limits a server would enforce
  later.

### 3.5 Connect a server (`/settings/connect`)

1. Server address field (07b `chooseServer` rules: HTTPS, `/api/v1/config` probe) and, when the
   server's `signupMode` is `invite`, an "Invite link" field (accepts the full invite URL or the
   token).
2. Username (pre-filled), password.
3. "Connect" → `authService.connectServer({ serverInput, inviteToken, username, password })`:
   `chooseServer` → `rebuildSignUpRequest` → `POST /auth/sign-up` with the rebuilt request, the
   invite token and the device label → on `201` save the session token (persisted as in sign-up),
   the server config, kv `account.mode = 'server'`, the username (if it changed), and the
   active-account record `mode: 'server'` (account key unchanged) → `accountMode.set('server')` →
   `syncService.start()` → banner "Connected. Your journal is uploading." (`connect.done`).
4. Errors: `usernameTaken` → "That username is taken." under the username field;
   `INVITE_INVALID`, `wrongPassword` and network errors use the 07b texts. A failed connect leaves
   the account local and unchanged (the server URL is cleared again).
5. After connecting, everything is spec 08/14 behavior: the outbox uploads the entries and media,
   eviction applies once objects are `uploaded`, and the Settings rows of server mode appear.

### 3.6 Text catalog additions

`welcome.local` "Use Without a Server", `localSetup.title` "Use Without a Server",
`localSetup.warning` (§3.1), `localSetup.understand` "I understand", `localSetup.create` "Create
Journal", `settings.localOnly` "Stored only on this phone", `settings.connect` "Connect a Server…",
`settings.erase` "Erase This Journal…", `settings.eraseText` "This deletes your journal from this
phone. It cannot be recovered unless you exported it.", `sync.localOnly` "This journal is stored
only on this phone.", `shared.localOnly` "Connect a server to share journals.", `connect.title`
"Connect a Server", `connect.invite` "Invite link", `connect.action` "Connect", `connect.done`
"Connected. Your journal is uploading."

## 4. Tests

- Core: `rebuildSignUpRequest` returns the stored wraps byte for byte; `loginKey` equals the one
  `deriveLoginMaterial` gives for the password; the result opens with `openKeyBundle` and the
  recovery path (`openRecovery`) to the same master key; a wrong password →
  `WrongPasswordError`.
- Client (Node, memory adapters, fake HTTP):
  1. `setUpLocal` makes **no** HTTP request; it stores kv `account.mode = 'local'` and the
     recovery wrap, ends `unlocked`, and an entry created afterwards stays in the outbox while
     `syncNow()` sends nothing.
  2. Restart (`restore()` with no server URL) → `locked` → password unlock works, and no key bundle
     is fetched.
  3. Local password change and local recovery change kv only and keep the master key (the old
     entries still decrypt).
  4. `connectServer` sends a sign-up whose `userId`, `homeCollection` and wraps equal the stored
     ones and whose `loginKey` derives from the password; on success the mode is `server` and the
     account key unchanged; `usernameTaken` leaves the mode `local`.
  5. `enforceCap` and `clearOriginals` delete nothing in local mode.
- e2e (`tests/e2e`): device A sets up locally (no server calls), creates 2 entries with media, then
  connects to the harness server and runs `syncUntilIdle`; device B signs in with the same
  username and password and decrypts both entries and one original.
- UI (Android, 07c §8 additions): **U-22** welcome → "Use Without a Server" → local setup →
  recovery key confirm → capture a photo → gallery shows the tile (`gallery.tilePending.0` is never
  shown: nothing is pending); **U-23** (seeded local account is not possible from Node, so it
  continues U-22's state in a second part): Settings › Account › Connect a Server… with the UI
  stack and an invite → `connect.done` → the tile is uploaded (server state check by sidecar).

## 4a. Implementation notes (2026-11-14, Opus)

- **Test-first slip:** the client auth additions (`setUpLocal`, `connectServer`, `eraseLocal`, the
  local branches of `restore`, `unlockWithPassword`, `changePassword`, `recover`) were written
  before their tests. The tests in `auth-service.test.ts` (`local-only mode (spec 20a)`, 6 tests)
  came right after and pass. The core function, the sync and eviction guards, and the e2e scenario
  were written red first.
- The mode lives in one observable created in `create-app-context.ts` and passed to the auth
  service (`AuthServiceContext.accountMode`, exposed as `auth.mode`, read by `useAccountMode()`).
  `localOnly` is a dependency of the sync service (a pass returns an empty report) and of the
  originals service (`enforceCap` and `clearOriginals` return). A mode change to `server` while
  unlocked starts sync and refreshes the account.
- Account key prefix `local` + `/` + userId. The active-account record is schema version 2 with
  `mode`.
- **Additions not in §3.3:** Sign Out is hidden in local mode, because sign-out destroys the stores
  and the phone holds the only copy ("Erase This Journal…" is the explicit path). On the recover
  screen the username field is hidden in local mode.
- The connect screen sends the invite field through `extractInviteToken`. A connect error clears
  the chosen server URL again.
- `src/testing.ts`: `serverUrl.choose` now probes `/api/v1/config` and returns the harness base
  URL, so e2e can connect. e2e `scenarios/local-only.test.ts` passes.
- Android flows: U-22 (`U-22-local-setup.yaml`) and U-23 (runs U-22's part again, then
  `U-23-connect.yaml`). The sidecar format has no post-run server check, so U-23 asserts
  `connect.done` only; the e2e scenario covers the upload. Both run in the batched Android round.

## 5. Verification checklist

- [ ] `pnpm verify`, e2e green.
- [ ] Android with no network at all: set up locally, take photos, lock and unlock with the
      fingerprint, restart the phone, export the journal (spec 20).
- [ ] Connect to the server later: all entries upload; a second device signs in and sees them.
- [ ] Settings in local mode show none of the server-only rows (§3.3).
