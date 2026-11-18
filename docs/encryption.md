# Encryption

Everything a person writes or records is encrypted on the device before it is stored or sent.
The server and the bucket hold ciphertext, wrapped keys and the facts they need to work (who, how
much, when). The full byte-level specification is [design/crypto.md](design/crypto.md); this page
is the overview.

## What the server can and cannot see

| Party | Sees | Never sees |
|---|---|---|
| Bucket provider | Object sizes, opaque object ids, upload and download times | Photos, videos, voice notes, thumbnails, notes, tags, dates, places, captions |
| Server operator | Usernames, entry and media counts and sizes, who shares with whom, sync timing, Argon2id parameters, wrapped keys, a hash of the login key | The password, the master key, any content or metadata |
| A locked phone | Ciphertext rows and objects; plain thumbnails in app-private storage unless **Encrypt thumbnails** is on | The master key (kept by Android Keystore) |

## Keys

```
password ─Argon2id─► KEK ─kdf─► login key (the server stores only its SHA-256)
                     KEK wraps the master key (random, 32 bytes)
recovery key (24 words) also wraps the master key
master key wraps: the X25519 private key, one key per collection
collection key wraps: one key per entry
entry key encrypts: the entry's metadata; wraps one key per media item
media key ─kdf─► original key and thumbnail key
```

Every account has a home collection; extra collections exist for sharing and public links. An
entry in several collections has one wrap of its key per collection.

## Primitives

| Use | Primitive (libsodium) |
|---|---|
| Password to key | Argon2id, at least 128 MiB; sign-up starts at 256 MiB × 16 and tunes to the device |
| Keys and small payloads (metadata, wrapped keys) | XChaCha20-Poly1305 sealed blob, padded to 256-byte blocks |
| Media files | STREAM format: 1 MiB chunks, each XChaCha20-Poly1305 with a counter nonce, so video can be decrypted by byte range |
| Sharing a collection key with another account | X25519 sealed box (`crypto_box_seal`) |
| Key derivation | BLAKE2b `crypto_kdf_derive_from_key` |

## Where the keys live

On the phone (Android app, `:data:secure`, F-KT-25):

| What | Where | Protection |
|---|---|---|
| Master key, phone with biometrics | `kodakiko.secure.vault` file, wrapped by the Keystore key `kodakiko.secure.vault.biometric.v1` (AES-256-GCM) | The key works only after a fingerprint or face check (BiometricPrompt with a CryptoObject); a newly enrolled fingerprint invalidates it, and the next unlock needs the password |
| Master key, phone with a screen lock but no biometrics | same file, wrapped by `kodakiko.secure.vault.device.v1` | The app asks for the device PIN, pattern or password before it unwraps the key |
| Master key, phone without a screen lock | not stored | The password is needed at every unlock |
| Session token, active account, server URL and config | `kodakiko.secure.sessions` and `kodakiko.secure.global`, encrypted with `kodakiko.secure.store.v1` | Keystore key, no user check; excluded from Android backup and device transfer |
| Master key, collection and entry keys while unlocked | Memory only, as byte arrays | Wiped at lock and sign-out; crypto refuses all-zero (wiped) keys, and running work is stopped before the keys are wiped |

The v0.3.1 secure items (`expo-secure-store`) are read once on the first start after the update,
moved to the stores above, and then deleted.

What stays unencrypted on the phone, in app-private storage: plain thumbnails unless **Encrypt
thumbnails** is on; the debug log (`files/logs/debug.log`: ids, counts, timings and error class
names, never content, see [features/settings.md](features/settings.md#debug-log)); and short-lived
temporary files (camera captures before import, decrypted copies for Share to apps). Temporary
files are wiped at app start, at unlock and at lock. Videos and voice notes play straight from the
encrypted file, so no decrypted copy is written.

The public link viewer (browser) keeps the link secret in the URL fragment and in page memory
only; its media service worker gets one key per video and forgets it when the page closes.

## Sharing and links

- **Shared journals:** the owner seals the collection key to the recipient's public key. The
  security code (a fingerprint of the public key) lets both people check they have the right key.
  Recipients can read, not change.
- **Public links:** the app re-encrypts copies of the chosen media under a key derived from a
  secret in the link's `#fragment`, which never reaches the server. Photo and video metadata (EXIF,
  GPS, file names) is removed from the copies.
- **Share to apps:** decrypts the chosen files for another app; those copies leave the encryption.

## Backups

Exports are plain zip files, or `.kdkx` files encrypted with a passphrase: a `KDKX` header with the
Argon2id parameters and salt, then the zip in the same STREAM chunk format. `pnpm --filter
@kodakiko/core decrypt-export` opens them without the app.
