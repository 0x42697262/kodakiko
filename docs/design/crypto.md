# Crypto specification

Design reference written before the build (2026-11-11). Where it differs from the code, the code
and the feature specs' implementation notes are right.

Client parts describe the Expo app (v0.1–v0.3); since v0.4.0 the client is the Kotlin Android app
(`apps/android`, [architecture.md](../architecture.md)) and there is no web app.

Normative: code in `packages/core/src/crypto/` must follow this file byte for byte. Any change needs a new format version and a migration on read (guidelines 4.4).

Reference designs: Ente's audited key hierarchy (F-ENTE-01..12), age's STREAM payload
(F-CRY-02), 1Password's share links (F-1P-01..05). Differences from Ente are listed in section 11.

## 1. Rules

1. Every primitive comes from libsodium through the `Sodium` port (section 2). No other crypto
   library and no self-written primitive. Exceptions, both platform key protection only:
   Android Keystore via `expo-secure-store`, and a non-extractable WebCrypto AES-GCM key on web
   (section 9).
2. One symmetric AEAD for everything: `crypto_aead_xchacha20poly1305_ietf` (key 32, nonce 24, tag
   16). One public-key primitive: `crypto_box_seal` (X25519) for sharing keys with other users.
3. Every ciphertext carries a format version byte. Every AEAD call binds a context string and the
   id of the row it belongs to through the additional data (AD), so the server cannot swap
   ciphertexts between rows or roles without detection.
4. Keys are `Uint8Array`. After use, buffers holding keys are overwritten with `fill(0)` (best
   effort; JavaScript gives no guarantee, documented as a limitation).
5. Text to bytes: UTF-8. Passwords are normalized with `String.prototype.normalize('NFKC')` before
   encoding so the same password gives the same key on every keyboard and platform.
6. Transport encoding of binary values in the API: base64url without padding (RFC 4648 §5).

## 2. The `Sodium` port

File: `packages/core/src/crypto/sodium.ts`. Implementations: `apps/client/src/adapters/sodium/`
`sodium.native.ts` (react-native-libsodium, F-LIB-01) and `sodium.web.ts`
(libsodium-wrappers-sumo, F-LIB-03; Argon2id runs inside a dedicated Web Worker so the page stays
responsive). Tests use a third implementation backed by libsodium-wrappers-sumo in Node with an
injectable deterministic `randomBytes`.

```ts
export interface Sodium {
  readonly randomBytes: (length: number) => Uint8Array;
  readonly argon2id: (input: Argon2idInput) => Promise<Uint8Array>;
  readonly kdfDerive: (subkeyLength: number, subkeyId: number, context: KdfContext, key: Uint8Array) => Uint8Array;
  readonly aeadEncrypt: (plaintext: Uint8Array, additionalData: Uint8Array, nonce: Uint8Array, key: Uint8Array) => Uint8Array;
  readonly aeadDecrypt: (ciphertext: Uint8Array, additionalData: Uint8Array, nonce: Uint8Array, key: Uint8Array) => Uint8Array;
  readonly boxKeypair: () => BoxKeypair;
  readonly boxSeal: (message: Uint8Array, recipientPublicKey: Uint8Array) => Uint8Array;
  readonly boxSealOpen: (ciphertext: Uint8Array, publicKey: Uint8Array, privateKey: Uint8Array) => Uint8Array;
  readonly genericHash: (outputLength: number, message: Uint8Array) => Uint8Array;
}

export type Argon2idInput = {
  readonly password: Uint8Array;
  readonly salt: Uint8Array;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
  readonly outputLength: number;
};

export type BoxKeypair = { readonly publicKey: Uint8Array; readonly privateKey: Uint8Array };
export type KdfContext = (typeof KDF_CONTEXTS)[keyof typeof KDF_CONTEXTS];
```

Mapping to libsodium (both implementations):

| Port method | libsodium function |
|---|---|
| `randomBytes` | `randombytes_buf` |
| `argon2id` | `crypto_pwhash(outputLength, password, salt, opsLimit, memLimitBytes, crypto_pwhash_ALG_ARGON2ID13)` |
| `kdfDerive` | `crypto_kdf_derive_from_key(subkeyLength, subkeyId, context, key)` (BLAKE2b) |
| `aeadEncrypt` / `aeadDecrypt` | `crypto_aead_xchacha20poly1305_ietf_encrypt` / `_decrypt` with `nsec = null` |
| `boxKeypair` | `crypto_box_keypair` |
| `boxSeal` / `boxSealOpen` | `crypto_box_seal` / `crypto_box_seal_open` |
| `genericHash` | `crypto_generichash(outputLength, message, null)` (BLAKE2b, unkeyed) |

`aeadDecrypt` and `boxSealOpen` throw `DecryptionError` (from `packages/core/src/crypto/errors.ts`)
on any failure; the adapters catch the library's own error and rethrow `DecryptionError`, never
leaking the library error text to the UI.

## 3. Constants

File: `packages/core/src/crypto/constants.ts`, all `Object.freeze`d.

| Name | Value | Meaning |
|---|---|---|
| `KEY_BYTES` | 32 | every symmetric key and the login key |
| `AEAD_NONCE_BYTES` | 24 | XChaCha20-Poly1305 nonce |
| `AEAD_TAG_BYTES` | 16 | Poly1305 tag |
| `SALT_BYTES` | 16 | Argon2id salt (`crypto_pwhash_SALTBYTES`) |
| `FORMAT_VERSION_SEALED_BLOB` | `0x01` | section 6 |
| `FORMAT_VERSION_SHARE_PAYLOAD` | `0x01` | section 8.1 |
| `STREAM_MAGIC` | ASCII `KDKS` (`0x4b 0x44 0x4b 0x53`) | section 7 |
| `STREAM_VERSION` | `0x01` | section 7 |
| `STREAM_CHUNK_SIZE_EXPONENT` | 20 | chunk = 2^20 = 1,048,576 plaintext bytes |
| `STREAM_NONCE_PREFIX_BYTES` | 16 | random per object |
| `STREAM_COUNTER_BYTES` | 7 | big-endian chunk counter |
| `STREAM_HEADER_BYTES` | 24 | section 7.1 |
| `PADDING_BLOCK_BYTES` | 256 | metadata padding block (section 6.2) |
| `ARGON2ID_MIN_MEM_BYTES` | 134,217,728 (128 MiB) | floor, enforced by client and server |
| `ARGON2ID_MIN_WORK` | 4,294,967,296 (1 GiB × 4) | floor for `memLimitBytes × opsLimit` |
| `ARGON2ID_START_MEM_BYTES` | 268,435,456 (256 MiB) | first attempt at sign-up |
| `ARGON2ID_START_OPS` | 16 | first attempt at sign-up |

`KDF_CONTEXTS` (exactly 8 ASCII bytes each, libsodium requirement):

| Key | Context | Subkey ids |
|---|---|---|
| `login` | `kdklogin` | 1 = login key (from KEK) |
| `recovery` | `kdkrecov` | 1 = recovery login key (from recovery key) |
| `media` | `kdkmedia` | 1 = original variant key, 2 = thumbnail variant key (from media key) |
| `link` | `kdklink_` | 1 = link collection key, 2 = link access token (from link secret) |

`AD_CONTEXTS` (UTF-8 strings used in additional data, section 6.1):

| Key | String | Binding id |
|---|---|---|
| `masterKeyByKek` | `kodakiko/v1/wrap/master-key-by-kek` | userId |
| `masterKeyByRecovery` | `kodakiko/v1/wrap/master-key-by-recovery` | userId |
| `recoveryKeyByMaster` | `kodakiko/v1/wrap/recovery-key-by-master` | userId |
| `privateKeyByMaster` | `kodakiko/v1/wrap/private-key-by-master` | userId |
| `collectionKeyByMaster` | `kodakiko/v1/wrap/collection-key-by-master` | collectionId |
| `entryKeyByCollection` | `kodakiko/v1/wrap/entry-key-by-collection` | `${collectionId}/${entryId}` |
| `mediaKeyByEntry` | `kodakiko/v1/wrap/media-key-by-entry` | `${entryId}/${mediaId}` |
| `entryMetadata` | `kodakiko/v1/entry-metadata` | entryId |
| `collectionMetadata` | `kodakiko/v1/collection-metadata` | collectionId |
| `userSettings` | `kodakiko/v1/user-settings` | userId |
| `indexSnapshot` | `kodakiko/v1/index-snapshot` | userId |
| `localKeyByDevice` | `kodakiko/v1/wrap/local-thumbnail-key` | userId (reserved, unused in v1) |
| `linkSecretByMaster` | `kodakiko/v1/wrap/link-secret-by-master` | link collectionId |

User, collection, entry and media ids are generated by the client before the first server call:
16 random bytes from `randomBytes`, base64url (22 characters). The server rejects ids that do not
match `^[A-Za-z0-9_-]{22}$` and ids that already exist under another owner.

## 4. Key hierarchy

    password ──NFKC, UTF-8──► Argon2id(salt, opsLimit, memLimit) ──► KEK (32)
    KEK ──kdf(1, "kdklogin")──► loginKey (32)           sent at sign-in; server stores SHA-256
    KEK ──AEAD wrap──► masterKey (MK, 32 random)         stored on server: masterKeyByKek
    recoveryKey (RK, 32 random)
      RK ──AEAD wrap──► MK                               stored on server: masterKeyByRecovery
      MK ──AEAD wrap──► RK                               stored on server: recoveryKeyByMaster
      RK ──kdf(1, "kdkrecov")──► recoveryLoginKey        server stores SHA-256
    MK ──AEAD wrap──► X25519 privateKey                  stored on server; publicKey in plaintext
    MK ──AEAD wrap──► collectionKey (CK, 32 random)      one per collection the user owns
    CK ──AEAD wrap──► entryKey (EK, 32 random)           one wrap per (collection, entry) membership
    EK ──AEAD──► entry metadata (sealed blob)
    EK ──AEAD wrap──► mediaKey (MDK, 32 random)          one per media item
    MDK ──kdf(1, "kdkmedia")──► originalKey ──STREAM──► original object
    MDK ──kdf(2, "kdkmedia")──► thumbnailKey ──STREAM──► thumbnail object

Every user owns exactly one **home collection** created at sign-up; every entry the user creates
is a member of it. Additional collections exist only for sharing (D-04, D-39) and for public
links (section 8.2). An entry can be a member of several collections; each membership stores its
own wrap of the same entry key.

## 5. Account flows

### 5.1 Sign-up

1. Validate username (`^[a-z0-9._-]{3,32}$` after NFKC + lowercase) and password strength
   (`packages/core/src/account/password-policy.ts`, spec 04 §2.3: at least 12 characters; reject
   if it equals the username or is one repeated character; the rule is pure and has no server
   copy, because the server never sees the password).
2. `salt = randomBytes(16)`.
3. Adaptive Argon2id (as Ente, F-ENTE-05): try `memLimit = 256 MiB, opsLimit = 16`. If the call
   throws (out of memory), halve `memLimit` and double `opsLimit`; stop with an error if
   `memLimit` would drop below 128 MiB. The product stays 4 GiB, which is `ARGON2ID_MIN_WORK`.
   **RISK-1:** this work factor mirrors Ente's "sensitive" preset and may take many seconds in
   single-threaded WASM (web, inside a Worker) and blocks the JS thread on Android (JSI call). Spec 04
   records a Node baseline; spec 07a measures it on a mid-range Android phone and a mid-range laptop browser; if either exceeds
   `ARGON2ID_BUDGET_MS` (8,000 ms), the programmer decides whether to lower
   `ARGON2ID_MIN_WORK` (a server migration of the CHECK constraint) before release. The
   Android UI shows a static "Securing your account…" screen before the call, so the frozen JS
   thread is not visible as a hang.
4. `KEK = argon2id(...)`, `loginKey = kdfDerive(32, 1, "kdklogin", KEK)`.
5. Generate `MK`, `RK`, keypair, home `CK` (all `randomBytes(32)` or `boxKeypair()`), and ids.
6. Build wraps: `masterKeyByKek`, `masterKeyByRecovery`, `recoveryKeyByMaster`,
   `privateKeyByMaster`, `collectionKeyByMaster` (home collection), and the encrypted home
   collection metadata (name "Journal").
7. `recoveryLoginKey = kdfDerive(32, 1, "kdkrecov", RK)`.
8. Send to `POST /auth/sign-up`: username, invite token, salt, opsLimit, memLimitBytes, loginKey,
   recoveryLoginKey, publicKey, all wraps, home collection. The server stores
   `SHA-256(loginKey)` and `SHA-256(recoveryLoginKey)` (F-TOOL-09), never the raw values.
9. Show RK as 24 words (section 5.6). The user must re-enter 3 randomly chosen words before the
   app continues.

### 5.2 Sign-in

1. `POST /auth/params {username}` returns `{salt, opsLimit, memLimitBytes}`. For an unknown
   username the server returns deterministic fake values (salt from HMAC-SHA-256 of a server
   secret and the username; start parameters), so the response does not reveal whether the user
   exists (D-41).
2. Client checks the parameters against the floor (section 3); below the floor means a hostile
   or broken server: stop with an error, do not derive.
3. Derive `KEK`, `loginKey`; `POST /auth/sign-in {username, loginKey}`.
4. Server compares `SHA-256(loginKey)` with the stored hash using `crypto.timingSafeEqual`;
   on success returns a session token and the key bundle (wraps, public key).
5. Client unwraps `MK`, then `privateKey`; discards `KEK` and `loginKey`.

### 5.3 Unlock (already signed in)

- Android: `MK` is read from the `KeyVault` (section 9.1). No Argon2id.
- Web: `MK` lives in memory; after a lock (D-26) the user re-enters the password and the client
  re-derives `KEK` locally from cached parameters, then unwraps the cached `masterKeyByKek`.
  No server call is needed to unlock.
- Stale cache (the password was changed on another device): if the local unwrap fails, the client
  calls `GET /auth/key-bundle` once (the session token is still valid unless the change revoked
  it), replaces the cached parameters and wrap, and retries with the same password. A second
  failure means a wrong password. A revoked session means a full sign-in (section 5.2).

### 5.4 Password change

1. User enters current and new password. Client re-derives the current KEK and checks it unwraps
   the stored `masterKeyByKek` (fails fast on a wrong password, no request sent).
2. New salt, adaptive Argon2id, new KEK, new loginKey, new `masterKeyByKek`.
3. `POST /auth/password` with current loginKey, new parameters, new loginKey, new wrap. Server
   verifies the current loginKey, swaps all three in one transaction, revokes every other session.

### 5.5 Recovery

1. User enters username and the 24 words. Client decodes RK (section 5.6); a checksum mismatch is
   a local error, no request sent.
2. `POST /auth/recovery/start {username}` returns `masterKeyByRecovery`. For an unknown username
   the server returns a deterministic fake of the same length (73 bytes = 1 + 24 + 32 + 16):
   byte 0 = `0x01`, bytes 1-72 = the first 72 bytes of
   `HMAC-SHA-256(secret, "recovery-fake-1" || username) || HMAC-SHA-256(secret, "recovery-fake-2" || username)
   || HMAC-SHA-256(secret, "recovery-fake-3" || username)`, where `secret` is the same server
   secret used for fake salts, and a fake `userId` = base64url of the first 16 bytes of
   `HMAC-SHA-256(secret, "recovery-fake-id" || username)`. Repeated calls return identical bytes, so the response does not
   reveal whether the user exists. Client unwraps MK with RK; failure means wrong words (or an
   unknown username; the message does not distinguish them).
3. User chooses a new password; client builds new salt, KEK, loginKey, `masterKeyByKek`.
4. `POST /auth/recovery/complete` with `recoveryLoginKey` and the new values. Server verifies
   `SHA-256(recoveryLoginKey)`, replaces login material, revokes all sessions.
5. RK stays the same (not rotatable, as Ente, F-ENTE-07).

### 5.6 Recovery key words

- Word list: the BIP-39 English list (2048 words), committed as
  `packages/core/src/crypto/wordlist-en.ts` (data only; source URL and SHA-256 of the original
  file recorded in `docs/facts.md` when added).
- Encoding: 256 bits of RK followed by 8 checksum bits = first byte of
  `genericHash(32, RK)` (BLAKE2b). 264 bits split into 24 groups of 11 bits, most significant
  first, each an index into the list.
- **Not BIP-39 compatible** (BIP-39 uses SHA-256 for the checksum; SHA-256 is not exposed by the
  native wrapper, F-LIB-02). The words must not be described as a BIP-39 phrase in the UI.
- Decoding: lowercase, trim, split on whitespace, map each word to its index (unknown word = error
  naming the word position), rebuild bits, verify checksum.

## 6. Sealed blob (keys and small payloads)

### 6.1 Format

    byte 0        FORMAT_VERSION_SEALED_BLOB (0x01)
    bytes 1-24    nonce (24 random bytes)
    bytes 25-     AEAD ciphertext (plaintext length + 16)

    AD = UTF-8(context) || 0x00 || UTF-8(bindingId)

Functions (`packages/core/src/crypto/sealed-blob.ts`):

```ts
export function sealBlob(sodium: Sodium, key: Uint8Array, plaintext: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export function openBlob(sodium: Sodium, key: Uint8Array, blob: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export function wrapKey(sodium: Sodium, wrappingKey: Uint8Array, keyToWrap: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export function unwrapKey(sodium: Sodium, wrappingKey: Uint8Array, wrapped: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
```

`openBlob` throws `UnsupportedFormatError` for an unknown version byte and `DecryptionError` for an
authentication failure. `unwrapKey` additionally checks the result is 32 bytes.

### 6.2 JSON payloads with padding

Entry metadata, collection metadata, user settings and the index snapshot are JSON with a
`schemaVersion` field. Before sealing: `UTF-8(JSON) -> pad`. Padding (ISO/IEC 7816-4): append
`0x80`, then `0x00` bytes until the length is a multiple of `PADDING_BLOCK_BYTES`. Unpadding
removes trailing `0x00` bytes and one `0x80`; anything else is `MalformedPayloadError`. This hides
the exact note length from the server (it still sees a 256-byte bucket).

Padding lives in `packages/core/src/crypto/padding.ts` (`padIso7816`, `unpadIso7816`), exported
so the vectors can test it directly.

Functions: `sealJson(..., value, ...)` and `openJson(..., schema, currentVersion, migrations,
...)`; `openJson` migrates older `schemaVersion` values on read through a per-payload migration
table, then parses with the Zod schema from `packages/contract`. Exact signatures and error rules:
`packages/core/src/crypto/sealed-blob.ts` and `packages/core/test/crypto.test.ts`.

## 7. STREAM object format (media originals and thumbnails, D-38)

### 7.1 Layout

    header (24 bytes):
      bytes 0-3     STREAM_MAGIC "KDKS"
      byte 4        STREAM_VERSION 0x01
      byte 5        STREAM_CHUNK_SIZE_EXPONENT (20)
      bytes 6-7     0x00 0x00 (reserved; reader rejects non-zero)
      bytes 8-23    noncePrefix (16 random bytes, new for every object)
    chunk 0 ciphertext, chunk 1 ciphertext, ... chunk n-1 ciphertext

    C          = 2^exponent plaintext bytes per chunk
    nonce_i    = noncePrefix (16) || uint56_be(i) (7) || finalFlag (1: 0x01 for i = n-1, else 0x00)
    AD         = utf8(lowercase hex of the 24 header bytes)   48 ASCII bytes (D-79)
    ct_i       = aeadEncrypt(pt_i, AD, nonce_i, variantKey)        length |pt_i| + 16

    Rule (D-79): every AEAD additional data in Kodakiko is valid UTF-8 text, because the Android
    libsodium wrapper (react-native-libsodium 1.7.0) accepts additional data only as a JS string
    (F-LIB-29). Hex keeps the encoding injective, so the whole header stays bound.
    variantKey = kdfDerive(32, variantId, "kdkmedia", mediaKey)    variantId 1 original, 2 thumbnail

Rules (as age, F-CRY-02):

- Every chunk except the last has exactly C plaintext bytes. The last has 1..C bytes, or 0 bytes
  only when the whole plaintext is empty (then n = 1).
- `n = max(1, ceil(P / C))`; object size `S = 24 + P + 16n`.
- From an object size: `n = ceil((S - 24) / (C + 16))`, `P = S - 24 - 16n`. A size that gives
  `n = 0` or a negative `P` is `MalformedObjectError`.
- Ciphertext chunk i starts at byte `24 + i × (C + 16)`.
- The plaintext size and the object size are also stored in the encrypted entry metadata; readers
  compare them and reject a mismatch.
- Why the 16-byte random prefix is enough: XChaCha20 derives a per-object subkey from the key and
  the first 16 nonce bytes (F-CRY-03), so the counter only has to be unique within one object.

### 7.2 Decryption rules

- Sequential: decrypt chunks in order; a chunk with `finalFlag = 1` must be the last bytes of the
  object (extra bytes = `MalformedObjectError`); reaching the end without a final chunk =
  `TruncatedObjectError`.
- Random access (video seeking, D-32): plaintext byte offset `o` lives in chunk `floor(o / C)`.
  The reader fetches only the needed ciphertext chunks with an HTTP Range request. Before serving
  any range, the reader decrypts the last chunk once with `finalFlag = 1` to prove the object is
  not truncated (age's seeking rule), using the size from the encrypted metadata.

### 7.3 Functions (`packages/core/src/crypto/stream.ts`)

```ts
export function createStreamHeader(sodium: Sodium): Uint8Array;
export function encryptChunk(sodium: Sodium, variantKey: Uint8Array, header: Uint8Array, index: number, isFinal: boolean, plaintext: Uint8Array): Uint8Array;
export function decryptChunk(sodium: Sodium, variantKey: Uint8Array, header: Uint8Array, index: number, isFinal: boolean, ciphertext: Uint8Array): Uint8Array;
export function parseStreamHeader(bytes: Uint8Array): StreamHeader;
export function streamLayout(plaintextSize: number, chunkSizeExponent: number): StreamLayout;
export function layoutFromCiphertextSize(ciphertextSize: number, chunkSizeExponent: number): StreamLayout;
export function chunkRangeForPlaintextRange(layout: StreamLayout, start: number, endInclusive: number): ChunkRange;
export function createStreamEncryptor(sodium: Sodium, variantKey: Uint8Array, plaintextSize: number): StreamEncryptor;
export function createStreamDecryptor(sodium: Sodium, variantKey: Uint8Array, header: Uint8Array, expectedPlaintextSize: number): StreamDecryptor;

export type StreamEncryptor = {
  readonly header: Uint8Array;
  readonly layout: StreamLayout;
  readonly encryptNext: (plaintextChunk: Uint8Array) => Uint8Array;
  readonly isDone: () => boolean;
};
export type StreamDecryptor = {
  readonly layout: StreamLayout;
  readonly decryptNext: (ciphertextChunk: Uint8Array) => Uint8Array;
  readonly finish: () => void;
};
```

The stateful encryptor and decryptor own chunk order, the final flag and truncation detection, so
platform adapters only move bytes:

- `createStreamEncryptor` builds the header and the layout from the plaintext size. Each
  `encryptNext` call takes exactly the next chunk (full size except the last) and sets
  `isFinal` itself from the layout. Calling it after the last chunk, or with a wrong size, throws
  `InvalidChunkError`.
- `createStreamDecryptor` takes the expected plaintext size from the encrypted entry metadata
  (never from the object), derives the layout, and decrypts chunks in order with the final flag
  set on the last index. `finish()` throws `TruncatedObjectError` if fewer chunks than the layout
  requires were decrypted. A server that truncates an object at a chunk boundary is caught twice:
  the size no longer matches the layout, and the last available chunk was sealed with
  `finalFlag = 0`, so decrypting it as final fails.

`index` is a JavaScript integer below 2^53; `uint56_be` writes it in 7 bytes. File I/O loops live in
the platform adapters (`FileStore`), which feed these functions one chunk at a time; peak
memory is two chunks (about 2 MiB) regardless of file size.

## 8. Sharing

### 8.1 With another user (flag `sharing`, read-only, D-39)

1. Owner fetches the recipient's public key: `GET /users/{username}/public-key`.
2. The app shows the key fingerprint (first 8 bytes of `genericHash(32, publicKey)` as 4 groups of
   4 hex characters) so both people can compare it in person. A malicious server could otherwise
   substitute a key (limitation, documented in `docs/design/security.md`).
3. Share payload: `0x01 || uint8(len(collectionIdBytes)) || UTF-8(collectionId) || CK`.
   `sealed = boxSeal(payload, recipientPublicKey)`. Sent with `POST /collections/{id}/shares`.
4. Recipient opens with `boxSealOpen`, checks the version byte and that the embedded collectionId
   equals the row's collectionId (else `DecryptionError`). The server keeps the sealed payload as
   the recipient's only copy of CK; the recipient opens it again after each unlock and holds CK in
   memory only.
5. Removing a recipient deletes their share row; no key rotation (D-31).

### 8.2 Public links (flag `public_links`, D-10)

1. `linkSecret = randomBytes(32)`; `linkCollectionKey = kdfDerive(32, 1, "kdklink_", linkSecret)`;
   `accessToken = kdfDerive(32, 2, "kdklink_", linkSecret)`.
2. The **client** builds everything; the server never sees the secret or any plaintext:
   1. creates a `link` collection whose `keyByOwner` is `linkSecret` wrapped by MK (context
      `linkSecretByMaster`, binding the link collection id), so the owner can rebuild the URL
      later; the collection key itself is never stored, it is derived from the secret;
   2. for each selected entry creates a `link_copy` entry with a fresh entry key wrapped under
      `linkCollectionKey` (context `entryKeyByCollection`), stripped metadata, and stripped media
      with fresh media keys (D-25, `docs/design/media.md` §6);
   3. uploads the stripped objects like any other object;
   4. calls `POST /public-links` with the collection id, the `accessToken`, expiry and view limit.
3. The server stores `SHA-256(accessToken)`, expiry, view limit; never the secret or the token.
4. URL: `https://<host>/s/<linkId>#<base64url(linkSecret)>`. The fragment is never sent to the
   server (F-1P-02). The viewer derives both keys and sends the access token in the
   `X-Kodakiko-Link-Token` header.
5. Revoke = `DELETE /public-links/{linkId}`: the server deletes the link row, the link collection,
   its `link_copy` entries and moves their objects to `deleting`; the token is refused afterwards
   (F-1P-05 warning applies).

## 9. Key storage on devices

### 9.1 Android

- `MK` is stored with `expo-secure-store` (Keystore-encrypted, excluded from backup, F-LIB-13)
  under the key `kodakiko.mk.<hash(accountKey)>` (`accountKey` = `<serverHost>/<userId>`, hash =
  first 16 hex characters of `genericHash`; spec 07a §4.4). When the device has biometrics enrolled the item is
  written with `requireAuthentication: true`; otherwise the app gates reads with
  `expo-local-authentication` (device PIN/pattern). Exact behavior per Android version is
  UNVERIFIED and is tested on a device in the client spec.
- If the Keystore item is invalidated (biometrics changed), the app falls back to the password
  (section 5.3 web path).
- Optional encrypted thumbnail mode (D-12): the device keeps the server's own encrypted thumbnail
  objects (spec 14 §5); no separate device key exists. The `localKeyByDevice` AD context is
  reserved and unused in v1.

### 9.2 Web

- Default: `MK` and the private key only in JavaScript memory of the page; cleared on lock, tab
  close, or sign-out. The cached `masterKeyByKek` and Argon2id parameters live in IndexedDB.
- "Keep me unlocked on this browser" (D-26, opt-in): generate a WebCrypto AES-GCM 256 key with
  `extractable: false`, store the `CryptoKey` object in IndexedDB, and store `MK` encrypted with it
  (12-byte random IV). This protects against copying the IndexedDB files to another machine but
  not against code running in this origin; stated in the setting's description.
- The service worker receives only per-playback `originalKey` values through `postMessage`
  (spec 15 §3.2),
  holds them in memory keyed by a random playback id, and drops them on lock (D-32).

## 10. Test vectors

- `packages/core/vectors/*.json`: generated once by `packages/core/scripts/generate-vectors.ts`
  using the Node test `Sodium` with a deterministic `randomBytes` (a counter-based byte source).
  `checkVectors` (pure, in core) replays them on any `Sodium` implementation.
- Vectors cover: Argon2id with small test parameters (8 MiB, ops 2; the floor applies to accounts,
  not to the primitive), KDF subkeys for each context, sealed blob for each AD context, padding
  edge cases (0, 1, 255, 256, 257, 512 bytes), STREAM objects of 0 bytes, 1 byte, C − 1, exactly
  C, C + 1 and 3C bytes, share payload, recovery words. Exact case lists: `packages/core/test/vectors.test.ts`.
- Both client adapters run the same vectors through the in-app diagnostics screen (spec 07a) on
  Android and in the browser; identical output is the proof that both wrappers behave the same
  (D-11).

## 11. Differences from Ente and why

| Topic | Ente | Kodakiko | Why |
|---|---|---|---|
| Login | SRP-6a with a KEK subkey | Send the KEK subkey; server stores SHA-256 | No SRP library to audit (D-24, D-11); same offline cost after a DB leak |
| Key wrapping | `crypto_secretbox` (no AD) | XChaCha20-Poly1305 with context + id in AD | Detects a server swapping wraps between rows |
| File payload | secretstream, 4 MiB | STREAM (age), 1 MiB | Seekable video (F-CRY-01, D-38); smaller first chunk for faster video start |
| Hierarchy | collection key wraps file keys | collection key wraps entry key, entry key wraps media keys | One wrap shares a whole moment with all its media |
| Metadata | not padded | padded to 256-byte blocks | Hides exact note length |
| Recovery words | BIP-39 (SHA-256 checksum) | BIP-39 word list, BLAKE2b checksum | SHA-256 missing in the native wrapper |
