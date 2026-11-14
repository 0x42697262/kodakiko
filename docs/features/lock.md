# App lock and privacy

The journal locks after a set time and on demand. Locking removes every key and every piece of
decrypted content from memory and temporary storage; unlocking returns to where the user was.
Code: `apps/client/src/services/lock/`, `features/lock/`, `adapters/keyvault/`,
`adapters/activity/`.

## When it locks

| Platform | Rule |
|---|---|
| Android | In the background longer than **Auto-lock** (Settings › Security, 1–60 min, default 5). Checked when the app returns to the foreground, before anything renders; a timer tries to lock earlier while backgrounded |
| Web | No input (pointer, key, wheel, touch) for **Auto-lock** (default 15 min). Checked every 30 s and when the tab becomes visible. Never when **Keep me unlocked** is on |

A playing video counts as activity. Exports pause auto-lock while they run. **Lock Now** (More tab
and gallery ⋮) locks at once; on the web it also turns Keep me unlocked off.

## What locking does

The account state becomes `locked` first, so the screens hide at once behind a cover, then each
step runs; a step that fails is skipped and the last one always runs:

1. Cancel an unfinished export part.
2. Stop sync.
3. Cancel downloads.
4. Release videos (the web service worker forgets its keys; Android deletes `play-*` files).
5. Release opened originals.
6. Drop decrypted thumbnails from memory.
7. Clear the capture tray and delete its unsaved files.
8. Delete every plaintext temp file.
9. Clear the index, the gallery filter and selection, and cached collection names.
10. Wipe all keys from memory.

The Android upload service keeps running while locked: it only moves ciphertext. Uploads that
finish meanwhile are confirmed at the next unlock.

Unlocking starts sync, rebuilds the index, sweeps orphan files, enforces the original cache size
and refreshes the account's role and quota.

## The lock screen

- Shows "Locked — (username)".
- Android with biometrics enrolled: the prompt appears once by itself, and **Unlock** asks again;
  **Unlock with Password** shows the password field. A cancelled or failed prompt falls back to the
  password.
- Wrong password: "That password is incorrect." and the field clears.
- **Forgot password?** opens recovery with the username filled in. **Sign Out** asks "Sign out?
  Unsynced changes on this device will be lost." when changes are still waiting.
- After unlock the app returns to the screen it showed before (if that entry still exists), else
  to the gallery.

Routes that work while locked: `/welcome`, `/invite`, `/sign-in`, `/sign-up`, `/securing`,
`/recover`, `/lock`, `/cancel-deletion` and public links `/s/[linkId]`. Every other route needs an
unlocked journal.

## Keys on the device

- **Android:** the master key is stored with `expo-secure-store` (Keystore, excluded from backups),
  bound to biometrics when they are enrolled. If biometrics change and the key becomes unreadable,
  the password unlocks instead.
- **Web:** the master key stays in page memory only. **Keep me unlocked on this browser** (Settings
  › Security, web only) stores it encrypted with a non-extractable AES-GCM key in IndexedDB and
  keeps the session across restarts; the setting warns that anyone using the browser profile can
  open the journal.

## Other privacy settings

| Setting | Effect |
|---|---|
| **Secure screen** (Android, default on) | Blocks screenshots, screen recording and the app preview in the recent-apps list (`FLAG_SECURE`) |
| **Hide note previews** | Tiles show no note text |
| **Encrypt thumbnails** (Android) | Keeps thumbnails encrypted on the device ([media-cache.md](media-cache.md)) |

## Checked only on a phone

Background auto-lock timing, biometric unlock and its fallback, and the secure screen.
