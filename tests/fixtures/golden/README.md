# Golden fixtures for the Kotlin port

Deterministic outputs of the TypeScript reference (`packages/core`) that the Kotlin client must
reproduce byte for byte. Everything except `manifest.json` (the judge's list of required files and
fields) and this README is generated:

```sh
pnpm --filter @kodakiko/core generate-goldens   # or: node packages/core/scripts/generate-goldens.ts
```

`packages/core/test/goldens.test.ts` regenerates into a temp directory and fails if any byte
differs, so the generator and the committed files never drift. Never edit the generated files by
hand.

## Common shape

Every JSON file is `{ "format": 1, "generator": "packages/core/scripts/generate-goldens.ts",
"cases": [...] }`, pretty-printed with 2 spaces and a trailing newline.

- Binary values are lowercase hex, except inside `request` objects (wire DTOs), which use the
  contract's encoding: unpadded base64url (`encodeBytes` in `packages/contract/src/bytes.ts`).
- Ids (`userId`, `homeCollectionId`, `bindingId`, ...) are 22-character unpadded base64url strings
  of 16 bytes, as on the wire.
- Timestamps are fixed strings; no clock is read.

## Determinism rules (mirror these in Kotlin)

### Counter random

`counterBytes(seed, length)[i] = (seed + i) & 0xff`.

`createCounterRandom(seed)` is one sequential byte stream whose byte number `j` (counting from 0
over all draws of that source) is `(seed + j) & 0xff`. A draw of `n` bytes after `D` bytes have been
drawn returns `counterBytes(seed + D, n)`. Each case creates a fresh source from its `randomSeed`;
`randomDraws` (where present) lists the lengths of every draw in order.

### Golden `Sodium`

The generator wraps `createNodeSodium()` (libsodium-wrappers-sumo 0.8.4) and replaces two members:

| Member | Golden behavior |
|---|---|
| `randomBytes(n)` | next `n` bytes of the case's counter random |
| `boxKeypair()` | `crypto_box_seed_keypair(seed)` with `seed` = next 32 bytes of the same counter random. libsodium computes `privateKey = SHA-512(seed)[0..32]` (unclamped, as returned) and `publicKey = crypto_scalarmult_base(privateKey)` |

All other members are plain libsodium: `argon2id` = `crypto_pwhash` (ALG_ARGON2ID13, 32-byte output),
`kdfDerive` = `crypto_kdf_derive_from_key`, AEAD = XChaCha20-Poly1305-IETF, `genericHash` = BLAKE2b
without key.

Ids are drawn from the same source the way the client does it:
`randomId = () => encodeBytes(randomBytes(16))`.

### Argon2id parameters

All account flows use `opsLimit 2`, `memLimitBytes 8388608` (8 MiB), the salt being the 16 bytes
the flow draws. They are passed as the `startParams` argument
`salt => ({ salt, opsLimit: 2, memLimitBytes: 8388608 })` of `createSignUpMaterial`,
`createRecoveryCompletion` and `createPasswordChangeMaterial`; `deriveWithAdaptiveParams` tries
them first and they succeed, so no adaptive fallback happens. `rebuildSignUpRequest` and the
current-password side of `createPasswordChangeMaterial` use `deriveLoginMaterial` with the stored
params. None of these functions checks `meetsKdfFloor` (only `deriveForSignIn` does, and it is not
used here), so the small params pass unchanged. Inputs carry them as `kdf: { opsLimit, memLimitBytes }`.

Derivations used throughout:

- `passwordBytes` = UTF-8 of the NFKC-normalized password.
- `kek` = `argon2id(passwordBytes, salt, opsLimit, memLimitBytes, 32)`.
- `loginKey` = `crypto_kdf_derive_from_key(32, 1, "kdklogin", kek)`.
- `recoveryLoginKey` = `crypto_kdf_derive_from_key(32, 1, "kdkrecov", recoveryKey)`.
- `wrapKey(wrappingKey, key, context, bindingId)` = sealed blob of the raw 32-byte key (no padding).

### Sealed blob and sealed JSON

`blob = 0x01 || nonce(24) || XChaCha20-Poly1305(plaintext, AD, nonce, key)` with
`AD = UTF-8(context) || 0x00 || UTF-8(bindingId)` and the nonce being one 24-byte draw.
`sealJson` seals `pad(UTF-8(JSON.stringify(value)))`, where `pad` is ISO/IEC 7816-4 to a multiple of
256 bytes (append `0x80`, then zeros; always at least one byte of padding).

### JSON serialization (`json` fields)

`JSON.stringify` without whitespace:

- Key order is the schema's declaration order (the generator passes each value through the zod
  schema's `parse`, which emits keys in shape order). Optional keys that are absent (`caption`,
  `favorite`) are omitted, never `null`.
- Escapes: `"` → `\"`, `\` → `\\`, `\b \f \n \r \t` as short escapes, every other U+0000–U+001F as
  `\u00xx` with lowercase hex. Everything else is emitted raw: non-ASCII, emoji, `/`, U+2028.
- Strings are not Unicode-normalized (`entry-non-ascii` contains a decomposed `e` + U+0301).
- Numbers use the shortest round-trip form and integral values have no fraction: `5`, not `5.0`
  (`entry-full` has `"accuracyMeters":5`); `12.5`, `-33.8688`, `52.370216` as written.

### STREAM objects

Header (24 bytes) = `"KDKS"` `0x01` (version) `0x14` (chunk size exponent 20) `0x00 0x00` +
16-byte nonce prefix (one 16-byte draw). Chunks of 2^20 plaintext bytes; at least one chunk (an
empty plaintext gives one empty final chunk). Chunk `i` nonce = prefix || `i` as 7-byte big-endian ||
`0x01` if final else `0x00`. AD = UTF-8 of the lowercase hex of the 24-byte header (48 ASCII bytes).
The object file is `header || chunk ciphertexts` (each plaintext chunk + 16-byte tag).

## Files and fields

### `crypto/sealed-json.json`

Six cases: `entry-minimal`, `entry-full` (tags, location, captions, favorite, photo + video +
audio), `entry-long-note` (note of exactly 100000 chars), `entry-non-ascii`, `collection-home`,
`collection-non-ascii`.

| Field | Meaning |
|---|---|
| `name` | case name |
| `schema` | `EntryMetadataV1` or `CollectionMetadataV1` |
| `context` | AD context string (`kodakiko/v1/entry-metadata` or `kodakiko/v1/collection-metadata`) |
| `bindingId` | AD binding id (the entry or collection id) |
| `key` | 32-byte key, hex |
| `randomSeed` | counter-random seed; the nonce is `counterBytes(randomSeed, 24)` |
| `json` | exact string that was sealed (before UTF-8 and padding) |
| `blob` | sealed blob, hex |

Kotlin: serialize the parsed `json` value with its own serializer, seal it with `key` and the
nonce, and compare with `blob`; open `blob` and compare with `json`.

### `crypto/stream-objects.json` + `crypto/stream-objects/<name>.kdks`

Cases `empty` (0 bytes), `five-bytes` (5), `one-chunk-exact` (1048576: one full final chunk),
`one-chunk-plus-one` (1048577: a full chunk + a 1-byte final chunk).

| Field | Meaning |
|---|---|
| `name` | case name, also the file name |
| `key` | 32-byte variant key, hex |
| `plaintextSeed` | plaintext is `counterBytes(plaintextSeed, plaintextSize)` |
| `plaintextSize` | plaintext length in bytes |
| `randomSeed` | the nonce prefix is `counterBytes(randomSeed, 16)` |
| `file` | object file path relative to `tests/fixtures/golden/` |
| `objectSha256` | SHA-256 of the whole object file, hex |
| `header` | the 24-byte header, hex |
| `chunkCount` | number of chunks |
| `ciphertextSize` | object file size in bytes |

### `account/signup.json`

`createSignUpMaterial` with the golden sodium. Cases: `ascii-password-open-signup` (no invite) and
`non-ascii-password-with-invite` (password with umlauts, a decomposed accent, CJK, emoji, the `ﬁ`
ligature and a fullwidth digit, so NFKC changes the bytes).

Draw order (`randomDraws` = `[16,32,32,32,32,16,16,24,24,24,24,24,24]`, byte offsets from
`randomSeed`): salt (0), masterKey (16), recoveryKey (48), homeCollectionKey (80), box keypair seed
(112), userId bytes (144), homeCollectionId bytes (160), then the six nonces: masterKeyByKek (176),
masterKeyByRecovery (200), recoveryKeyByMaster (224), privateKeyByMaster (248),
homeCollection.keyByOwner (272), homeCollection.metadataBlob (296).

Wraps: `masterKeyByKek` = wrap(kek, masterKey, `kodakiko/v1/wrap/master-key-by-kek`, userId);
`masterKeyByRecovery` = wrap(recoveryKey, masterKey, `.../master-key-by-recovery`, userId);
`recoveryKeyByMaster` = wrap(masterKey, recoveryKey, `.../recovery-key-by-master`, userId);
`privateKeyByMaster` = wrap(masterKey, privateKey, `.../private-key-by-master`, userId);
`keyByOwner` = wrap(masterKey, homeCollectionKey, `.../collection-key-by-master`, homeCollectionId);
`metadataBlob` = sealJson(homeCollectionKey, metadata, `kodakiko/v1/collection-metadata`, homeCollectionId).

| `inputs` field | Meaning |
|---|---|
| `username` | normalized username passed to the core |
| `password` | password as typed |
| `homeCollectionName`, `createdAtUtc` | home collection metadata values |
| `randomSeed` | counter-random seed of the flow |
| `randomIdBytes` | bytes per random id (16) |
| `kdf` | `{ opsLimit, memLimitBytes }` returned by `startParams` |
| `inviteToken`, `deviceLabel` | wire-only values the client adds to the request (not core inputs) |

| `outputs` field | Meaning |
|---|---|
| `randomDraws` | draw lengths in order |
| `passwordBytes` | NFKC UTF-8 password, hex |
| `salt`, `kek`, `loginKey`, `masterKey`, `recoveryKey`, `recoveryLoginKey`, `homeCollectionKey` | hex |
| `boxSeed`, `publicKey`, `privateKey` | box keypair seed and keypair, hex |
| `recoveryWords` | the 24 words (`encodeRecoveryKey(recoveryKey)`) |
| `userId`, `homeCollectionId` | ids |
| `homeCollectionMetadataJson` | exact JSON sealed into `metadataBlob` |
| `request` | the `signUpRequest` wire body, keys in the client's order |

### `account/recovery.json`

Both cases recover the account of `ascii-password-open-signup`. `openRecovery` then
`createRecoveryCompletion`. Draws: `[16, 24]` = new salt, then the `masterKeyByKek` nonce.

- `plain-words`: the words joined by single spaces.
- `messy-words-non-ascii-password`: leading and trailing whitespace, `" \t\n "` separators, every
  word at index `i % 3 == 0` uppercase, `i % 3 == 1` in fullwidth letters (U+FF41...), and a
  non-ASCII new password.

| `inputs` field | Meaning |
|---|---|
| `username` | wire username |
| `userId` | account id (AD binding of every wrap) |
| `masterKeyByRecovery` | wrapped master key from the server, hex |
| `wordsInput` | recovery words as typed |
| `newPassword` | new password |
| `randomSeed`, `kdf`, `deviceLabel` | as in sign-up |

| `outputs` field | Meaning |
|---|---|
| `normalizedWords` | `normalizeRecoveryInput(wordsInput)`: NFKC, lowercase, split on Unicode whitespace, empties dropped |
| `recoveryKey`, `masterKey` | decoded and unwrapped, hex |
| `randomDraws` | `[16, 24]` |
| `passwordBytes`, `salt`, `kek`, `loginKey`, `recoveryLoginKey`, `masterKeyByKek` | hex |
| `request` | the `recoveryCompleteRequest` wire body |

### `account/password-change.json`

`ascii-to-non-ascii-password`: `createPasswordChangeMaterial` on the account of
`ascii-password-open-signup`. Current kek from `currentPassword` + `currentKdf`; master key
unwrapped from `cachedMasterKeyByKek` (context `.../master-key-by-kek`, binding `userId`); draws
`[16, 24]` = new salt, new `masterKeyByKek` nonce.

| `inputs` field | Meaning |
|---|---|
| `userId`, `currentPassword`, `newPassword` | as named |
| `currentKdf` | `{ salt (hex), opsLimit, memLimitBytes }` of the current password |
| `cachedMasterKeyByKek` | current wrapped master key, hex |
| `randomSeed`, `kdf` | as in sign-up |

| `outputs` field | Meaning |
|---|---|
| `randomDraws` | `[16, 24]` |
| `currentKek`, `currentLoginKey`, `masterKey` | hex |
| `passwordBytes`, `salt`, `kek`, `loginKey`, `masterKeyByKek` | the new password's material, hex |
| `request` | the `passwordChangeRequest` wire body |

### `account/local-account.json`

`rebuildSignUpRequest` (local account connecting to a server). No randomness is drawn.

- `fresh-local-account`: stored data of `non-ascii-password-with-invite`; its `request` equals that
  sign-up case's `request` exactly.
- `local-account-after-password-change`: stored data of `ascii-password-open-signup` with
  `kdfParams` and `masterKeyByKek` replaced by the password-change output; password = the new one.

| `inputs` field | Meaning |
|---|---|
| `password`, `username` | as typed / normalized |
| `stored` | `StoredLocalAccount`: `userId`, `kdfParams { salt, opsLimit, memLimitBytes }`, `masterKeyByKek`, `masterKeyByRecovery`, `recoveryKeyByMaster`, `publicKey`, `privateKeyByMaster`, `homeCollection { id, keyByOwner, metadataBlob }`; bytes as hex |
| `inviteToken`, `deviceLabel` | wire-only values |

| `outputs` field | Meaning |
|---|---|
| `passwordBytes`, `kek`, `loginKey`, `masterKey`, `recoveryKey`, `recoveryLoginKey` | hex |
| `request` | the rebuilt `signUpRequest` wire body |

### `contract/dto-samples.json`

One case per zod schema exported by `@kodakiko/contract` (79), sorted by `schema`.

| Field | Meaning |
|---|---|
| `schema` | export name in `@kodakiko/contract` |
| `valid` | JSON values the schema accepts (one per branch where there are branches) |
| `invalid` | JSON values the schema rejects, each wrong for one reason |

The `valid` samples of `signUpRequest`, `recoveryCompleteRequest` and `passwordChangeRequest` are
the `request` outputs of the account files. Zod behaviors the Kotlin decoders must match:

- Unknown keys are stripped, not rejected (`z.object` default).
- Query schemas (`*ListQuery`, `linkEntriesQuery`, `diffQuery`) coerce numbers: `"20"` is valid,
  `"0"`, `"many"` and a missing `since` are invalid.
- `collectionDto.recipientCount` may be absent (defaults to `null`).
- Timestamps (`z.iso.datetime()`) require `Z`; offsets like `+01:00` and date-only values fail.
- `bytesSchema`: non-empty unpadded base64url whose length is not `1 (mod 4)`.
- `adminUserPatchRequest` needs at least one of `quotaBytes` / `role`.
- `presignResponse` and `linkDownload` are discriminated unions (`mode`, `kind`).
