# v0.3.1 device fixture (K007)

What a real Kodakiko v0.3.1 install (Expo / React Native) leaves on an Android device. Used by the
v0.4.0 Kotlin app's migration tests (`docs/planning/v0.4.0/migration.md`, tasks K006, K100–K107).
Captured 2026-11-14 on the `akon` emulator (API 34, Google APIs image, x86_64) from a v0.3.1
**debug** build (versionCode 301; native code and JS identical to the v0.3.1 tag), pulled with
`adb root` from `/data/data/com.x42697262.kodakiko`.

Test accounts only. The server was the throw-away UI stack (deleted after capture), so the session
token inside is dead and the passwords below protect nothing real.

## `server/` — server account `k007server`

Device PIN set (no fingerprint), so the master key is stored in mode `device` (not biometric).

| Content | State |
|---|---|
| Entries | 8 (seeded through the TS client, synced to the device): photos, MP4, MOV, WebM, PNG, a one-year-old entry (On This Day), markdown notes, tags `beach`, `summer`, `travel`, `video`, `trash-me` |
| Favorites | 2 set before sync; 1 more toggled **on the device while offline** |
| Caption + place | entry index 7 (caption "Sunset over the bay", device location) |
| Voice note | 1 audio item (`audio/webm`) on entry index 0 |
| Trash | 1 entry (index 6) |
| Pinned (Keep Offline) | 1 entry; its original downloaded (`objects/*.kdks`, ~6.5 MB) |
| Outbox | 1 `updateEntryMeta`, `queued`, attempts 1, `NETWORK` (the offline favorite) |
| Entry states | `synced` + one `pending_update` |
| Thumbnails | 8 plain WebP in `thumbs-plain/` (default mode) |
| Secure store | `kodakiko.server`, `.serverConfig`, `.activeAccount`, `.session.<acct>`, `.mk.<acct>`, `.mkauth.<acct>` |

`account.json`: username, password, the 24 recovery words, server input, entry ids in seed order.

## `local/` — local-only account `k007local`

Created with "Use without a server"; one camera photo (emulator virtual scene). Entry
`pending_create`, outbox `createEntry` queued, objects `local`, kv `account.mode` and
`account.masterKeyByRecovery` present. Secure store: `.activeAccount`, `.mk.<acct>`,
`.mkauth.<acct>`. `account.json`: username and password.

## Files

- `files/SQLite/kodakiko-<acct>.db` + `-wal` + `-shm`: **as captured, never checkpointed.** The
  `.db` file is only 4 KB; every row is in the WAL. Always copy the three files together; open a
  copy, never these files (opening with `sqlite3` checkpoints and rewrites them).
- `files/kodakiko/<acct>/objects/*.kdks`, `thumbs-plain/*.webp`: as on the device.
- `shared_prefs/SecureStore.xml`: expo-secure-store items. The ciphertext needs the Android
  Keystore key of that install, which cannot be exported: **these are format references only**;
  reading them for real happens on a device in the upgrade path (K105, U-41).
- `listing.txt`: `find` of the whole app data dir at capture time (incl. `cache/`).
- Not kept: the React Native dev JS bundle, ExoPlayer cache DB, unrelated preferences.

## Known gaps

1. No **biometric-bound** master-key item (no fingerprint enrolled): K105 tests it live on the
   emulator after enrolling one.
2. No `settings.device` kv row (no setting was changed): tests use synthesized JSON as well.
3. No shared journal, public link or encrypted-thumbnail mode.

## Reproduce

Tooling is local and not committed (`scripts/local/k007/`: `capture.ts` + four Maestro flows),
run against `~/projects/dev/kodakiko-v031-debug-x86_64.apk`
(sha256 `5a5b0876430b766f87c1eac78a75b99bb24fd5fbe4b10f3d15b5374a3bfc8610`).
