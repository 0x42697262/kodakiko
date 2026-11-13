# 16 — App lock and lifecycle

Status: draft 1 (2026-11-11).

Goal: the journal locks itself after the configured time in the background (Android) or idle (web),
or on demand; locking reliably removes every key and every piece of decrypted content from memory
and temporary storage; unlocking brings the app back where the user was. Web gets the opt-in "Keep
me unlocked on this browser" (D-26, D-63).

Read first: `docs/design/client.md` §6; `docs/design/crypto.md` §9; `docs/design/security.md` §§1-2;
spec 07a (account state, `KeyVault`, `Lifecycle`), 07b (auth service, `/lock`), 08 (sync), 09
(temp files, tray, device settings), 11 (index, thumbnail source), 13 (original source), 14
(downloader, thumbnail cache), 15 (video source). Depends on 07b; its lock/unlock steps call
services of 08-15, so it is built after 15.

## 1. Lock service (`src/services/lock/lock-service.ts`)

```ts
export type LockReason = 'manual' | 'idle' | 'background' | 'signOut' | 'sessionInvalid';
export type LifecycleStep = { readonly name: string; readonly run: () => Promise<void> | void };
export type LockService = {
  readonly lock: (reason: LockReason) => Promise<void>;
  readonly afterUnlock: () => Promise<void>;
  readonly isLocking: () => boolean;
};
export function createLockService(deps: { readonly lockSteps: readonly LifecycleStep[]; readonly unlockSteps: readonly LifecycleStep[]; readonly accountState: AccountStateStore; readonly log: (event: string, data: Record<string, unknown>) => void }): LockService;
```

- `lock(reason)`: if already locked or locking → return. Set the account state to `locked`
  **first** (the router guard hides content immediately, §4), then run every lock step in order;
  a step that throws is logged (`lock.step_failed` with the step name only) and the next step still
  runs. The key-wiping step is last and runs even if every other step failed. From this spec on,
  the state change to `locked` no longer wipes keys (07b note); `wipeKeys` does.
- `afterUnlock()`: called by the auth service (07b) right after the state becomes `unlocked`; runs
  unlock steps in order; failures logged and skipped.
- The auth service's sign-out (07b §2 step 10) calls `lock('signOut')` before deleting stores.
- 07a's `onSessionInvalid` calls `lock('sessionInvalid')` and then moves the state to `signedOut`.

These step lists **replace** the on-unlock / on-lock subscriptions that earlier specs wired
themselves (08 `syncService.start/stop`, 11 `indexService.build/clear`, 09 tray/temp wipes, 13-15
`clearAll`/`releaseAll`, 14 `cancelBackground`, 17 `collectionNames.clear`): remove those
subscriptions when implementing this spec, so each action runs exactly once. Step lists are built
once in `create-app-context.ts` (one wiring place), exactly in this order
(steps 0 and 10 exist once specs 20 and 17 are built; until then they are absent):

| # | Lock step | Calls |
|---|---|---|
| 0 | `cancelExport` | `exportService.cancel()` (20; aborts an unfinished export part) |
| 1 | `stopSync` | `syncService.stop()` (08) |
| 2 | `cancelDownloads` | `downloader.cancelBackground()` and abort in-flight downloads (14) |
| 3 | `releaseVideo` | `videoSource.releaseAll()` (15: web `forgetAll` to the worker, Android deletes `tmp/play-*`) |
| 4 | `releaseOriginals` | `originalSource.clearAll()` (13) |
| 5 | `clearThumbnails` | `thumbnailSource.clear()` (11/14: revokes object URLs, drops data URIs) |
| 6 | `clearTray` | `captureTray.clear(true)` (09: deletes unsaved captures' objects) |
| 7 | `wipeTemp` | `tempFiles.wipe()` (09) |
| 8 | `clearIndex` | `indexService.clear()` (11) |
| 9 | `clearUiState` | gallery filter store and selection reset (11), pending `returnTo` kept (§4) |
| 10 | `clearCollectionNames` | `collectionNames.clear()` (17) |
| 11 | `wipeKeys` | `entryKeys.clear()` (08) then the account state's `AccountKeys` buffers `wipe`d and dropped (07a) |

| # | Unlock step | Calls |
|---|---|---|
| 1 | `startSync` | `syncService.start()` (first pass reconciles uploads finished while locked) |
| 2 | `buildIndex` | `indexService.build()` (11) |
| 3 | `sweepOrphans` | `fileStore.sweepOrphans(known ids, ORPHAN_SWEEP_AGE_MS)` (09) |
| 4 | `enforceCache` | `originalsService.enforceCap()` (14) |
| 5 | `refreshMe` | `accountService.refreshMe()` (19; absent until spec 19) |

The Android native upload service keeps running while locked: it only moves ciphertext and needs no
keys; confirmations happen on the next unlock (08 §3.6 `reconcileUploads`).

## 2. Auto-lock (`src/services/lock/auto-lock.ts`)

```ts
export type ActivitySource = { readonly lastActivityAt: () => number; readonly touch: () => void; readonly start: () => void; readonly stop: () => void };
export function createAutoLock(deps: { readonly mode: 'background' | 'idle'; readonly lifecycle: Lifecycle; readonly clock: Clock; readonly settings: DeviceSettings; readonly lockService: LockService; readonly accountState: AccountStateStore; readonly keepUnlocked: () => boolean; readonly activity: ActivitySource }): { readonly start: () => void; readonly stop: () => void; readonly pause: () => void; readonly resume: () => void };
```

`pause()` suspends both the background and the idle checks (used while an export runs, spec 20
§7.4); `resume()` resets the reference time to now and re-enables them. Manual lock still works
while paused. `mode` is `'background'` on Android and `'idle'` on web (wired by
`create-app-context.ts` from the platform file `src/constants/auto-lock-mode.native.ts` / `.web.ts`);
only the logic of that mode runs (web visibility changes must not trigger the Android rule).
Constants `ACTIVITY_THROTTLE_MS`, `IDLE_CHECK_INTERVAL_MS` live in `src/services/lock/constants.ts`.

Started on every transition to `unlocked`, stopped on every transition away. The timeout is
`autoLockMinutes` (device settings, spec 09 §4.2: Android default 5, web 15, range 1-60).

Android (background-based, D-26):
- `lifecycle.subscribeActive(false)` → record `backgroundedAt = clock.now()` and schedule a
  `setTimeout` for the timeout (best effort: JavaScript timers may not run while the app is
  suspended).
- `subscribeActive(true)` → clear the timer; if `now − backgroundedAt ≥ timeout` → `lock('background')`
  **before** anything else renders (the guard of §4 shows `/lock`). This check is the reliable path.
- The timer firing while still in the background → `lock('background')`.

Web (idle-based):
- `ActivitySource` (`src/adapters/activity/activity.web.ts`): `pointerdown`, `keydown`, `wheel`,
  `touchstart` on `document` (passive listeners) update `lastActivityAt`, throttled to one update
  per `ACTIVITY_THROTTLE_MS = 5000`. The native implementation is a no-op.
- An interval of `IDLE_CHECK_INTERVAL_MS = 30_000` plus every `visibilitychange` to visible checks
  `now − lastActivityAt ≥ timeout` → `lock('idle')` (browsers throttle timers in background tabs;
  the visibility check catches up).
- When `keepUnlocked()` is true (§3), web never idle-locks (D-63).

Activity while a video plays (no input events) must not lock it: the player screen (15) calls
`activity.touch()` every `ACTIVITY_THROTTLE_MS` while playing.

## 3. Keep me unlocked on this browser (web only, D-26, D-63)

`src/adapters/keyvault/keyvault.web.ts` replaces 07a's memory-only version (same interface plus
two methods):

```ts
readonly enablePersistence: (accountKey: string, masterKey: Uint8Array) => Promise<void>;
readonly disablePersistence: (accountKey: string) => Promise<void>;
```

- Storage: IndexedDB database `kodakiko-keys` (global, not per account; created with `idb`),
  object store `vault`, key = `hashAccountKey(sodium, accountKey)`, value `{ schemaVersion: 1,
  wrappingKey: CryptoKey, iv: Uint8Array, wrappedMasterKey: Uint8Array }`.
- `enablePersistence`: `crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false,
  ['encrypt', 'decrypt'])` (non-extractable, crypto.md §9.2; the one allowed non-libsodium use,
  crypto.md §1 rule 1); `iv = crypto.getRandomValues(new Uint8Array(12))`; `wrappedMasterKey =
  encrypt(AES-GCM, iv, additionalData = utf8(\`kodakiko/v1/web-keep-unlocked/${accountKey}\`))`;
  store. (Session persistence is handled by the auth service, below.) Storage goes through
  `VaultStorage = { get(id): Promise<VaultRecord | null>; put(id, record): Promise<void>; delete(id):
  Promise<void> }` (idb implementation; tests use a `Map`).
- `loadMasterKey`: with a stored record → decrypt (failure → delete the record, return `null`);
  without → the in-memory value as before.
- `disablePersistence`: delete the record. `forget(accountKey)` (sign-out) also deletes it.
- `capabilities(accountKey)` (the signature gains `accountKey` on both platforms): `persistent` =
  a record exists for that account.

Auth service additions (07b): `restore()` on web, after step 1.4 (state `locked`), tries
`keyVault.loadMasterKey`; a master key → unwrap `privateKeyByMaster` from kv with it (exactly as
07b `unlockWithDevice`) → `unlocked` without the lock screen.
`setKeepUnlocked(enabled: boolean)`: enabling requires the account to be unlocked (uses the
in-memory master key) → `enablePersistence` and re-saves the session token with `persist: true`;
disabling → `disablePersistence` and re-saves the token with `persist: false` (back to
`sessionStorage`). `lockNow()`: on web first
`setKeepUnlocked(false)`, then `lock('manual')` (D-63).

The setting lives in Settings › Security (spec 20 screen), with the description: "Stay unlocked in
this browser, even after closing it. Anyone who can use this browser profile can open your
journal. Locking manually turns this off." Shown only on web.

## 4. Route guard and return-to

- `app/_layout.tsx` subscribes to the account state. When it becomes `locked` while a protected
  route is visible: remember `returnTo` (the current path, only if it is a protected route, never
  `/lock`) in the account-state store (07a's store shape gains `returnTo: string | null`), then `router.replace('/lock')`. While the transition happens
  a full-screen `surface`-colored view covers the content (no flash of decrypted content).
- After unlock: navigate to `returnTo` if set (and still valid: entry routes check the entry exists),
  else `/`; clear `returnTo`.
- Unprotected routes (exactly): `/welcome`, `/invite`, `/sign-in`, `/sign-up`, `/securing`,
  `/recover`, `/lock`, `/cancel-deletion`, `/s/[linkId]`. Every other route is protected (including
  `/recovery-key`, `/trash`, `/day/[date]`, `/play/…`).

## 5. Lock screen (`/lock`, replaces 07b's version where they differ)

- Android: on mount, if `KeyVault.capabilities(accountKey).biometric`, prompt once automatically
  (prompt text `lock.biometricPrompt`); buttons "Unlock" (prompt again) and "Unlock with Password".
  Any error from the prompt (cancel, lockout, "authentication already in progress") → stay on
  `/lock`, show the password field, log only the error class name.
- Password field (web always; Android after "Unlock with Password"): Enter submits; wrong password
  → `lock.wrongPassword` "That password is incorrect." under the field; the field is cleared.
- Links: "Forgot password?" → `/recover` with the username prefilled; "Sign Out" → confirmation
  sheet "Sign out? Unsynced changes on this device will be lost." when the outbox has queued items
  (else no sheet) → sign-out.
- The username is shown ("Locked — (username)") so a shared device makes clear whose journal it is.

## 6. Manual lock

- Gallery header overflow menu (11) and Settings (20): "Lock Now" → `lockNow()`.
- Web keyboard shortcut: none in v1.

## 7. Text catalog additions

| Key | Text |
|---|---|
| `lock.lockNow` | `Lock Now` |
| `lock.lockedAs` | `(username) => \`Locked — ${username}\`` |
| `lock.forgot` | `Forgot password?` |
| `lock.signOut` | `Sign Out` |
| `lock.signOutConfirm` | `Sign out? Unsynced changes on this device will be lost.` |

| `settings.keepUnlocked` | `Keep me unlocked on this browser` |
| `settings.keepUnlockedHint` | `Stay unlocked in this browser, even after closing it. Anyone who can use this browser profile can open your journal. Locking manually turns this off.` |

## 8. Tests (Node; fake lifecycle, fixed clock, fake services recording calls)

1. `lock()` runs the steps in the table order (12 with specs 17 and 20 built); with steps 3 and 7 throwing, all others still
   run and `wipeKeys` runs last; state is `locked` before step 1 runs; a second `lock()` during the
   first is a no-op.
2. `afterUnlock()` runs the 4 steps in order; a failing step does not stop the rest.
3. Android auto-lock: background at t, active at t + 4:59 → not locked; at t + 5:00 → locked
   before the active listener returns; timer firing in background → locked.
4. Web idle: activity at t, check at t + 14:59 → not locked; t + 15:00 → locked; visibility change
   triggers a check; `keepUnlocked` true → never locks; `activity.touch()` from the player keeps it
   unlocked.
5. Keep-unlocked vault with Node's `globalThis.crypto.subtle` and an in-memory fake of the IndexedDB
   store (the vault's storage is behind a small `VaultStorage` interface for this reason):
   enable → load returns the same master key; tampered `wrappedMasterKey` → `null` and record
   deleted; disable → `null`; the stored `CryptoKey` has `extractable === false`.
6. Return-to logic (pure function `nextRouteAfterUnlock(returnTo, entryExists)`).

## 8a. Implementation notes (2026-11-12, Opus)

- `LockService` adds `bind()` (one subscription: leaving `unlocked` by any other path runs the lock
  steps once; entering `unlocked` runs `afterUnlock()`, so the auth service needs no new call),
  `idle()` and `lockCount()`. `bindAccountLifecycle` is no longer used by the app (kept for
  `src/testing.ts` and its tests).
- The account keys are still wiped by the auth service's state subscriber at the moment the
  state leaves `unlocked`, which is earlier than step 11. Step `wipeKeys` clears the entry-key cache.
  Steps 0 and 10 (specs 20, 17) are absent. Extra steps: `loadSettings` (unlock step 0) and
  `boundSettings.unload()` inside `clearUiState`.
- Sign-out: `AuthServiceContext.beforeSignOut` runs `lock('signOut')` and then cancels native
  uploads. Session invalid: `lock('sessionInvalid')`, then `signedOut`, then cancel uploads.
- `KeyVault.capabilities(accountKey?)`; web adds the optional `keyVault.keepUnlocked`
  (`enable` / `disable`). The logic is `createPersistentVault` (`persistent-vault.ts`,
  `VaultStorage`); IndexedDB is opened lazily. Auth adds `setKeepUnlocked`, `keepUnlocked` and
  `activeUsername`; `services.lock` (`LockControl`) holds `lockNow`, `setKeepUnlocked`, `autoLock`
  (pause/resume for spec 20), `activity`, `returnTo` and `lockedInSession`.
- `createAutoLock` takes `onVisible` (page visibility; no-op on Android) as a dependency.
- `returnTo` is a separate observable recorded by `ReturnToRecorder` in `app/_layout.tsx`, not a
  field of the account-state store. Route components already render `<Redirect>` in the same
  render as the state change, so there is no cover view.
- The lock screen auto-prompts for biometrics only after an in-session lock; at app start,
  `restore()` already prompts (it also loads the web keep-unlocked key).
- The "Keep me unlocked" switch is added with the Settings screen (spec 20). The "Key material
  present?" diagnostics probe is not built yet (open item).

## 9. Verification checklist

- [ ] `pnpm verify` green.
- [ ] Android: open an entry, press Home, wait past the auto-lock time (set 1 minute), reopen →
      `/lock` appears with no flash of the entry; biometric unlock → back on the same entry.
- [ ] Android: lock during an upload of a large video → the upload notification continues; unlock
      → the entry becomes synced.
- [ ] Android, lock while a video is playing → playback stops and `tmp/` is empty (diagnostics
      list).
- [ ] Web: idle 1 minute (setting) → `/lock`; watching a 3-minute video does not lock.
- [ ] Web: enable "Keep me unlocked", close the browser, reopen → the gallery opens without a
      password; "Lock Now" → password needed again, and after reload still locked.
- [ ] Memory check (dev build): after lock, the diagnostics "Key material present?" probe (added
      to spec 07a's diagnostics screen by this spec: reports `accountState` key fields non-null?,
      `entryKeys` cache size, `collectionNames` cache size, thumbnail-source LRU size; all must be
      0/false) (reads the
      account-state store and the entry-key cache sizes) reports none.
- [ ] After lock, web DevTools → Application → the media service worker's map is empty (debug
      message), and no `blob:` thumbnails remain in the DOM.
