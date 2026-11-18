# Accounts

Signing up, signing in, recovery, password change, sessions and account deletion. The crypto
behind it is in [../encryption.md](../encryption.md) and [../design/crypto.md](../design/crypto.md).
Code: `:shared:account` (KDF policy, sign-up/in material, recovery, password change),
`:service:account` (`AuthService`: account state, sign-up/in, unlock, recovery, sign-out),
`:ui:feature:auth` (Welcome, sign-up, sign-in, recover, recovery key, cancel deletion),
`:ui:feature:settings` (Settings › Account), `:app` (`settings/AccountAdapters.kt`,
`log/LoggingAuthService.kt`), and on the server `apps/server/src/services/` (auth, accounts,
invites).

## Starting out

**Welcome:** an "Invite link or server address" field and Continue, or **Use without a
server** ([local-mode.md](local-mode.md)). The address must be `https://` and answer as a Kodakiko
server ("That address isn't a Kodakiko server."; unreachable: "Can't reach the server. Check your
connection or VPN, then try again."). There is no web app since v0.4.0; a browser only opens public
links ([public-links.md](public-links.md)).

## Sign-up

- Needs an invite link (`/invite#<token>`) unless the server runs open sign-up (`SIGNUP_MODE`).
  Invites carry a role (member or admin), expire (default 7 days) and work once; a used, revoked
  or expired one answers "This invite link is invalid, used or expired. Ask for a new one."
- **Username:** 3–32 lowercase letters, digits, dots, dashes or underscores, normalized (NFKC,
  trimmed, lower case). Taken: "That username is taken."
- **Password:** at least 12 characters (counted after NFKC), not the username, not one repeated
  character; typed twice.
- "Securing your account… This takes a few seconds." Argon2id starts at 256 MiB × 16 passes; on a
  device without enough memory it halves memory and doubles passes, never below 128 MiB and
  1 GiB × 4 of total work. The server refuses parameters below that floor.
- The client creates the master key, recovery key, X25519 key pair and home collection, and sends
  only wrapped keys, the public key, the Argon2id parameters and the login key (the server stores
  its SHA-256).
- **Recovery key:** 24 words (BIP-39 English list) shown once — "Write these 24 words down and keep
  them somewhere safe… Nobody, including the server's owner, can recover your journal without them."
  — then a check of a few words ("That doesn't match. Check your written copy.").

## Sign-in

- `GET /auth/params?username=` returns the user's salt and Argon2id parameters; unknown usernames
  get stable fake parameters, so the endpoint does not reveal who exists. Parameters below the
  floor stop sign-in ("The server asked for unsafe security settings…").
- The device derives the login key and sends it; wrong password and unknown user both answer
  "Username or password is incorrect."
- After 10 failures within 60 minutes for a username, each attempt waits longer (1 s doubling, up
  to 30 s). Rate limits answer "Too many attempts. Wait a minute and try again."
- A session token (`kdk_s_…`, the server keeps its SHA-256) expires after 30 days without use.
  It is kept in the app's own encrypted store on the phone. The key bundle (wrapped keys) is
  downloaded and unwrapped on the device.
- Unlock start and end (method, outcome, error class, duration) go to the
  [debug log](settings.md#debug-log); usernames, passwords and keys never do.

## Recovery and password change

- **Forgot password?** — username, the 24 words and a new password. The recovery key unwraps the
  master key, new login material is created, and every session of the account is ended. Unknown
  usernames get deterministic fakes. Invalid words: "Word (n) isn't in the recovery word list." or
  "These words don't form a valid recovery key."
- **Change Password…** (Settings › Account) — current and new password; re-wraps the master key
  under the new password, ends every other session and issues a new token. "Password changed. Other
  devices need the new password the next time they unlock."
- **Show Recovery Key…** asks for the password, then shows the 24 words; they are dropped when the
  screen closes or the app locks.

## Account deletion

**Delete Account…** asks for the username and the password, then schedules deletion after the
server's grace period (default 7 days) and ends every session. Signing in during the grace period
opens "Account Scheduled for Deletion — Your journal will be erased on (date). You can still keep
it." with **Keep My Account**; while pending, the server answers every other request with
`ACCOUNT_PENDING_DELETION`. When the grace period ends, a server job erases the account's rows and
objects.

## Settings › Account

Username, server host, storage used ("Using 340 KB of 100 GB"), the security code (for sharing),
**Server and keys** ([settings.md](settings.md#server-and-keys)), Change Password…, Show Recovery
Key…, Delete Account… (in the error color). Since v0.4.1 the rows that open something have icons
(Connect a Server, Server and keys, Change Password, Show Recovery Key, Delete Account).
**Change Server…** signs out ("(n) unsynced changes on this device will be lost.") and returns to
Welcome. Local-only accounts get **Connect a Server…** and **Erase This
Journal…** instead.

## Sign-out

Gallery ⋮ › Sign Out and the last row of Settings (hidden for local-only accounts) lock, end the
session on the server and delete this device's copy of the journal. With changes still waiting to
upload, Settings asks "Sign out? Unsynced changes on this device will be lost." first; the lock
screen's Sign Out always asks.

## Checked only on a phone

The biometric prompt during the first unlock after updating from v0.3.1 ([lock.md](lock.md)).
