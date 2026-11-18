# App lock and privacy

The journal locks after a set time and on demand. Locking removes every key and every piece of
decrypted content from memory and temporary storage; unlocking returns to where the user was.
Code: `:service:lock` (`LockService`, `AutoLock`, `UnlockedWork`, `ReturnTo`), `:ui:feature:lock`
(lock screen, `BiometricDeviceGate`), `:data:secure` (key vault and stores), `:data:migration`
(v0.3.1 adoption), `:platform:device` (secure screen), `:app` (`gallery/LifecycleSteps.kt`,
`edit/EditFlushingLockService.kt`, `log/StepTiming.kt`).

## When it locks

- In the background longer than **Auto-lock** (Settings › Security and privacy: 1, 2, 5, 10, 15,
  30 or 60 min; default 5). Checked when the app returns to the foreground, before anything
  renders; a timer tries to lock earlier while the app is in the background.
- Exports pause auto-lock while they run.
- **Lock Now** (More tab, the gallery ⋮ and the Settings root) locks at once.

v0.3.1's web rules (idle time in a browser tab, **Keep me unlocked on this browser**) are gone with
the web app.

## What locking does

1. **Before** the account state turns locked, an open edit page is saved (5 s limit), so nothing
   typed is lost ([edit-page.md](edit-page.md#saving-done-back-and-discard-changed-in-v041)). Then
   all unlocked work (decrypting, sharing and link writes) is canceled and waited for, so no
   decryption can still be running when the keys go (since v0.4.0, the lock fence).
2. The account state becomes `locked`, so the screens hide at once behind the lock screen.
3. The lock steps run in order; a step that fails is recorded and skipped, and the last one always
   runs:
   1. Cancel an unfinished export part.
   2. Stop sync and cancel link uploads.
   3. Cancel downloads.
   4. Release videos.
   5. Release opened originals.
   6. Drop decrypted thumbnails from memory.
   7. Clear the capture tray and delete its unsaved files; forget the gallery picks waiting for a
      delete-from-device offer ([capture.md](capture.md)).
   8. Wipe the plaintext temp directory (the whole app cache).
   9. Clear the index.
   10. Clear UI state (gallery filter, notices, loaded settings).
   11. Clear cached collection names.
   12. Clear the account's cached details.
   13. Flush edits (a last save of any open edit page) and drop an unfinished voice recording.
   14. Wipe all keys from memory, wipe the temp directory once more and release every decrypted
       share copy, so nothing that a late task wrote survives.
4. With the debug log on, each step's duration (and any failure, by error class) is recorded
   ([settings.md](settings.md#debug-log)).

Every key is a byte array and is overwritten with zeros when wiped. Since v0.4.0 the crypto code
refuses an all-zero key, and entry keys are handed out as short-lived copies checked against the
lock generation, so work that races a lock fails instead of writing with a wiped key.

Uploads stop at lock and continue after the next unlock. (v0.3.1's Android upload service, which
kept moving ciphertext while locked or in the background, has not been ported yet: uploads run
only while the app is open.)

## Unlocking

Unlock steps, in order: load settings (and wipe the temp directory first), start sync, build the
index, sweep orphan files, enforce the originals cache size, refresh the account's role and quota.
The temp directory is also wiped at every app start, so files left by a killed app never survive.

## The lock screen

- "Journal Locked" with "Locked — (username)". The lock screen always blocks screenshots and the
  recent-apps preview, whatever **Secure screen** says.
- When the key is stored on the phone, the system prompt ("Unlock Kodakiko": fingerprint, face, or
  the screen lock) appears once by itself, and **Unlock** asks again; **Unlock with Password** shows
  the password field. A canceled or failed prompt falls back to the password.
- Wrong password: "That password is incorrect." and the field clears.
- **Forgot password?** opens recovery with the username filled in. **Sign Out** asks "Sign out?
  Unsynced changes on this device will be lost."
- A journal whose database cannot be read shows "Your journal could not be opened." It is never
  deleted automatically.
- After unlock the app returns to the screen it showed before (if that entry still exists), else
  to the start tab.

Routes that work while locked: Welcome, invite, sign-in, sign-up, securing, recover, the lock
screen, cancel deletion and local setup. Every other route needs an unlocked journal. Public links
open in a browser, not in the app.

## Keys on the device

The master key is stored with Android Keystore keys of Kodakiko's own (details and aliases in
[../encryption.md](../encryption.md#where-the-keys-live)): bound to biometrics when they are
enrolled, else to the screen lock; on a phone without a screen lock it is not stored and the
password is needed at every unlock. If biometrics change and the key becomes unreadable, the
password unlocks instead.

### Updating from v0.3.1

The first start after installing v0.4.x over v0.3.1 adopts the existing install in place: the
same database, files and settings, and the master key stored by v0.3.1. The app reads that key
with the old Keystore key (so the phone may show its biometric prompt), then stores it the new way
and deletes the old item. If that is not possible, the lock screen says "Kodakiko was updated.
Enter your password to unlock." Nothing is re-encrypted and nothing needs to sync again.

## Privacy on screen

| Setting | Effect |
|---|---|
| **Secure screen** (default on) | Blocks screenshots, screen recording and the app preview in the recent-apps list (`FLAG_SECURE`); always on while locked |
| **Hide note previews** (off) | Tiles show no note text |
| **Encrypt thumbnails** (off) | Keeps thumbnails encrypted on the device ([media-cache.md](media-cache.md)) |
| **Biometric unlock** | An info row: on when the phone has biometrics and the key is stored with them |

Copies of a public-link URL or a note are marked sensitive on the clipboard, so Android's clipboard
preview hides them.

## Checked only on a phone

Background auto-lock timing, biometric unlock and its fallback, the first unlock after updating
from v0.3.1, and the secure screen.
