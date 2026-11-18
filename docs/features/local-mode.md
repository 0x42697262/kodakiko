# Local-only mode

Kodakiko can run with no server at all: the journal lives only on the phone, encrypted exactly as
it would be for a server, and can move to a server later without re-encrypting anything. Code:
`:shared:account` (local account material), `:service:account` (`setUpLocal`, `connectServer`,
`eraseLocal`, the `mode` flow), `:ui:feature:auth` (`LocalSetupScreen`, `ConnectServerScreen`),
`:ui:feature:settings` (Account, Sync and Data and storage rows that change in local mode).

## Setting up

Welcome › **Use Without a Server** (`/local-setup`):

1. A warning, "This phone will hold the only copy of your journal until you connect a server.
   Export it from Settings to keep a backup.", with a required **I understand**.
2. Username (same rules as a server account), password and confirmation.
3. **Create Journal** derives keys exactly like a server sign-up (same Argon2id parameters, master
   key, recovery key, key pair, home collection) and stores everything a sign-up request would
   need, without any network call. Then the recovery-key screen and the gallery.

The account's local storage label is `local/<userId>` and its mode (`account.mode`) is `local`.

## What changes in local mode

| Place | Local mode |
|---|---|
| Sync | Never runs; edits only queue outbox items, which upload after connecting. No sync banner, no pull to refresh |
| Original cache | Never evicted (the phone holds the only copy) |
| Limits | The defaults a server would enforce: 20 items per entry, 3.5 GiB per file |
| Settings › Account | Username, "Stored only on this phone", Connect a Server…, Change Password… and Show Recovery Key… (both offline), Erase This Journal… |
| Settings › Sync | "This journal is stored only on this phone." and Connect a Server… |
| Settings › Data and storage | Usage and Backup and restore; cache size, clearing originals, Autoplay on mobile data and the Wi-Fi rule are hidden |
| More tab | Pause sync is hidden |
| Settings root | Links, Admin and Sign Out are hidden; Sync stays and offers Connect a Server… |
| Sharing, public links, admin, security code, Change Server | Hidden; the bottom bar has no Shared tab |
| Share sheet | To apps only; sharing one entry opens the Android share chooser directly ([sharing.md](sharing.md#the-share-sheet)) |
| Forgot password? | Recovery with the 24 words, offline |
| Sign Out | Hidden; Erase This Journal… is the explicit way out |

## Connecting a server

Settings › Account › **Connect a Server…** (`/settings/connect`):

1. Server address (HTTPS, checked against `/api/v1/config`) and, when the server uses invites, the
   invite link.
2. Username (filled in) and password.
3. **Connect** rebuilds the sign-up request from the stored keys (unwrapping them with the password
   to compute fresh login keys), posts it as a normal sign-up with the same user id, home
   collection and key wraps, saves the session and switches the mode to `server`. "Connected. Your
   journal is uploading." Sync then uploads every entry and item.
4. A taken username ("That username is taken."), a bad invite or a wrong password leave the account
   local and unchanged.

The storage label stays `local/<userId>` after connecting; it is only a name for the local stores.

## Erasing

**Erase This Journal…** asks for the username and the password, then deletes the stores, the
stored key and the journal from the phone: "This deletes your journal from this phone. It cannot
be recovered unless you exported it."

## Backups

Local journals are the only copy, so backups matter: Settings › Data and storage › **Create
backup** (see [backup.md](backup.md)).

## Checked only on a phone

UI flows U-22 (set up and capture) and U-23 (connect a server) cover the mode on the emulator;
they were not rerun for v0.4.1 (no automated device runs in this release).
