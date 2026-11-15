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

## Function-case files (domain, media, index, sync, export)

Every file under `domain/`, `media/`, `index/`, `sync/` and `export/` holds cases of the shape
`{ "name", "fn", "args", "result" }` (some add fields such as `files`; they are listed per file).

- `name`: unique within the file. It starts with the function (or group) and continues with the
  name of the TS unit test it comes from (`it(...)` title or `it.each` row name). Names containing
  `edge:` are extra cases that have no TS test of their own.
- `fn`: the exported TS function (or `Object.method` for stateful objects) that produced `result`.
- `args`: a JSON object whose keys are the function's parameter names; the call passes them in the
  order documented for each `fn` below. No clock is read and no unseeded randomness is used.
- `result`: the return value, encoded as below, or `{ "error": "<ClassName>", "message": "<message>" }`
  when the call throws (`ClassName` = `error.constructor.name`, so `Error`, `RangeError`,
  `TypeError` or a Kodakiko error class; `message` exact).

### Encoding of non-JSON values (`toJson` in `packages/core/scripts/goldens/shared.ts`)

| TS value | JSON |
|---|---|
| `Uint8Array` | lowercase hex string |
| `Date` | `toISOString()` (`YYYY-MM-DDTHH:mm:ss.sssZ`); an invalid `Date` is the string `"Invalid Date"` |
| `Map` | array of `[key, value]` pairs in the map's insertion order |
| `Set` | sorted array (numbers numerically, strings by UTF-16 code unit) |
| `undefined` as a whole `result` | `{ "$undefined": true }` |
| object property whose value is `undefined` | the key is omitted (absent), which is different from `null` |
| `NaN`, `Infinity`, `-Infinity` | `{ "$number": "NaN" }` / `{ "$number": "Infinity" }` / `{ "$number": "-Infinity" }` |
| `-0` | `0` |
| `bigint` | decimal string |

Object keys keep the order the TS code produced them in (for metadata: the zod schema's shape
order). Kotlin should compare parsed JSON values, not key order, unless a section says otherwise.
Numbers are JS doubles; integral values have no fraction (`5`, not `5.0`).

### Binary files

Binary inputs and outputs are files next to the JSON, referenced from `args` or `result` by an object:

- `{ "file": "<path>", "size": n, "sha256": "<hex>" }`: the whole content is that file
  (`path` relative to `tests/fixtures/golden/`).
- `{ "size": n, "sha256": "<hex>", "segments": [...] }`: a large, mostly constant content stored
  sparsely. Concatenate the segments in order: `{ "offset", "size", "file" }` = the bytes of that
  file, `{ "offset", "size", "fill": v }` = `size` bytes of value `v`. `sha256` is the hash of the
  concatenated content.

Identical contents are stored once, so two references can name the same file.

### Shared behaviors the Kotlin port must mimic

- JS default `Array.prototype.sort()` on strings and `<` / `>` on strings compare UTF-16 code
  units, not code points: an astral character (surrogate pair `D83C ...`) sorts before U+E000–U+FFFF.
  Kotlin `String.compareTo` does the same; `Collator` or code-point comparisons do not.
- `String.prototype.normalize('NFKC')` = `java.text.Normalizer.normalize(s, NFKC)`.
- `toLowerCase()` is locale-independent (`Locale.ROOT`): `İ` → `i` + U+0307.
- `trim()` and regex `\s` (with or without the `u` flag) match: U+0009–U+000D, U+0020, U+00A0,
  U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, U+FEFF. Not U+001C–U+001F, not
  U+0085, not U+200B. Kotlin `trim()`/`isWhitespace()` differ; use an explicit set.
- `\d` without the `u` flag (and with it) matches only ASCII `0-9`.
- `String.length` counts UTF-16 code units (an emoji counts 2).
- `Date.parse` accepts the ECMAScript date-time string format (`YYYY-MM-DDTHH:mm[:ss[.sss]]` with `Z`
  or `±HH:mm`, and date-only `YYYY-MM-DD` as UTC); anything else is `NaN`, and
  `new Date(NaN).toISOString()` throws `RangeError("Invalid time value")`. `toISOString()` always
  prints milliseconds and a 4-digit year for years 0000–9999 (6 digits with a sign otherwise).

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

### `domain/entry-time.json`

From `packages/core/test/entry-time.test.ts` (`packages/core/src/domain/entry-time.ts`).

| `fn` | Call |
|---|---|
| `localDateOf` | `localDateOf(args.takenAtUtc, args.utcOffsetMinutes)` → `"YYYY-MM-DD"` |
| `localTimeOf` | `localTimeOf(args.takenAtUtc, args.utcOffsetMinutes)` → `"HH:mm"` |
| `localPartsToUtc` | `localPartsToUtc(args.date, args.time, args.utcOffsetMinutes)` → ISO string or `null` |

Behavior:

- `localDateOf`/`localTimeOf`: `iso = new Date(Date.parse(takenAtUtc) + offset * 60000).toISOString()`,
  then characters `[0, 10)` (date) or `[11, 16)` (time). Inputs without milliseconds or with an
  explicit `+02:00` offset are accepted (`Date.parse`); an unparsable input throws
  `RangeError` `"Invalid time value"`.
- `localPartsToUtc`: `date` must match `^(\d{4})-(\d{2})-(\d{2})$` and `time` `^(\d{2}):(\d{2})$`
  (ASCII digits only, no seconds, no trailing text), hours < 24, minutes < 60, and the date must
  exist (proleptic Gregorian, year 0000 is a leap year). Years are never remapped (`0050` stays
  50 AD). Result = local time minus the offset; `null` if the UTC year is outside 0000–9999.
- Not covered: `currentTimeZone` (reads the runtime's time zone; no deterministic golden).

### `domain/calendar.json`

From `packages/core/test/calendar.test.ts` (`packages/core/src/domain/calendar.ts`).

| `fn` | Call |
|---|---|
| `monthGrid` | `monthGrid(args.yearMonth, args.todayLocalDate, args.weekStartsOn)` (`weekStartsOn` 0 = Sunday, 1 = Monday) |
| `addMonths` | `addMonths(args.yearMonth, args.delta)` |
| `yearMonthOf` | `yearMonthOf(args.localDate)` |
| `isValidYearMonth` | `isValidYearMonth(args.value)` |

- `monthGrid` returns 6 rows of 7 cells `{ localDate, inMonth, isToday }`. The first cell is the
  first day of the month moved back `(dayOfWeek(first) - weekStartsOn + 7) % 7` days (0 = Sunday).
  Cells are consecutive days. `localDate` is `YYYY-MM-DD` with a 4-digit zero-padded year;
  `isToday` is string equality with `todayLocalDate`, so it can be `true` on a cell outside the month.
- `addMonths`: `total = year * 12 + (month - 1) + delta`, `year' = floor(total / 12)`, month from
  the remainder, formatted `YYYY-MM` with padding (`0001-01` − 1 → `0000-12`).
- `yearMonthOf` = the first 7 characters (shorter input is returned unchanged).
- `isValidYearMonth` = `^\d{4}-(0[1-9]|1[0-2])$` (ASCII digits).

### `domain/tags.json`

From `packages/core/test/tags.test.ts` and `tags-edit.test.ts` (`packages/core/src/domain/tags.ts`).
`TAG_MAX_CHARS` = 64, `TAGS_PER_ENTRY_MAX` = 50 (from `@kodakiko/contract`).

| `fn` | Call |
|---|---|
| `normalizeTag` | `normalizeTag(args.input)` → string or `null` |
| `sortTags` | `sortTags(args.tags)` → new sorted array |
| `addTag` | `addTag(args.tags, args.raw)` → `{ tags, rejected }`, `rejected` ∈ `null`, `"empty"`, `"tooLong"`, `"duplicate"`, `"tooMany"` |
| `removeTag` | `removeTag(args.tags, args.tag)` |

- Normalization: NFKC, then `trim()`, then every `\s+` run → one space, then `toLowerCase()`
  (in that order). `null` when empty or longer than 64 UTF-16 code units. See the shared behaviors
  for the whitespace set.
- `sortTags` = JS default sort (UTF-16 code units).
- `addTag` checks in this order: empty, too long, duplicate (exact match in `tags`), full
  (`tags.length >= 50`); on rejection `tags` is returned unchanged (unsorted if it was unsorted);
  on success the list plus the new tag, sorted.
- `removeTag` normalizes `tag`; `null` (blank) returns the list unchanged; otherwise removes every
  element equal to it.

### `domain/entry-edit.json`

From `packages/core/test/entry-metadata.test.ts`, `favorites.test.ts` and `limits.test.ts`
(`packages/core/src/domain/entry-metadata.ts`, plus `mergeMetadata`/`metadataEquivalent` from
`src/sync/merge.ts` and `attributesMatch` from `src/index/gallery-index.ts` for the favorites test).

| `fn` | Call |
|---|---|
| `buildNewEntryMetadata` | `buildNewEntryMetadata(args.draft)`; `draft` = `{ media, times, note, tags, mediaLocations, deviceLocation, timeZone, nowUtc }` |
| `applyEntryEdit` | `applyEntryEdit(args.current, args.edit, args.nowUtc)` |
| `mergeMetadata` | `mergeMetadata(args.ancestor, args.local, args.server, args.nowUtc)` |
| `metadataEquivalent` | `metadataEquivalent(args.left, args.right)` |
| `attributesMatch` | `attributesMatch(args.entry, args.filters, args.homeCollectionId)`; `entry` is a partial `IndexedEntry` (only the fields the filters read) |
| `MIN_OBJECT_BYTES` | no call: `result` = `{ MIN_OBJECT_BYTES, STREAM_MIN_OBJECT_BYTES }`, which must be equal |

Encoding: metadata objects are `EntryMetadataV1` JSON. In `edit`, an absent key means "leave
unchanged"; `"location": null` means "clear the location". `captions` is an object `mediaId → text`.
A `result` metadata has the schema's key order; `favorite` is present only when `true`
(`"favorite": false` never appears) and `caption` only when non-empty.

Behavior:

- `buildNewEntryMetadata`: taken time and offset = the earliest of `times` by `Date.parse`
  (ties keep the first); no times → `RangeError` `"At least one capture time is required."`.
  Location = the point of `mediaLocations[i]` whose `times[i]` is earliest among non-null points
  (strictly earlier wins, so ties keep the first), as `{ latitude, longitude, accuracyMeters: null, source: "exif" }`;
  otherwise the device location with its accuracy and `source: "device"`; otherwise `null`.
  Tags: each normalized (a `null` → `InvalidEditError` `"A tag is empty or too long after normalizing."`),
  deduplicated, sorted. `createdAtUtc` = `editedAtUtc` = `nowUtc`.
- `applyEntryEdit` applies, in order: `mediaOrder` (must be a permutation of the current ids,
  else `InvalidEditError` `"The media order must list every media item exactly once."`),
  `removeMediaId` (unknown id → `"That media item is not part of this entry."`), `addMedia`
  (appended), `captions` (trimmed; empty after trim removes the caption; ids not in the media are
  ignored; applies to media added in the same edit); then an empty media list →
  `"An entry must keep at least one photo or video."`. `favorite` = `edit.favorite` if present, else
  the current flag. Tags are cleaned like on build. `time` replaces `takenAtUtc` and
  `utcOffsetMinutes` but not `timeZone`. `editedAtUtc` = `nowUtc`.

### Media conventions (all `media/*.json` files)

Generated by `packages/core/scripts/goldens/media-image.ts` (`sniff`, `exif`, `capture-time`,
`geometry`) and `media-video.ts` (`isobmff`, `strip`). Every case is `{ name, fn, args, result }`;
some cases have an extra `readStats` field (see below). Names are `<fn>: <TS test name>`, with
` (<variant>)` appended when one TS `it` checks several inputs, and `edge:` for extra cases.
`constant: ...` cases have `fn` = the name of an exported constant, `args` = `{}` and `result` = its value.

**Encoding of values**

- `result` is the return value encoded with the shared `toJson` rules: object keys in the TS
  declaration order, `null` fields kept as `null`, `Uint8Array` → lowercase hex, `-0` → `0`.
  Numbers are JS shortest round-trip doubles (`2.2944999999999998`, `48.8584`). Parse them as
  `Double` and compare exactly: the Kotlin math has to produce the same double.
- A thrown error becomes `{ "error": "<class name>", "message": "<message>" }`. The classes that
  appear are `UnsupportedVideoError` (strip) and `RangeError` (`earliestTime`).
- Short binary arguments are inline hex: `header` (sniff), `moov` (moovHasMetadata), and `bytes`
  (byteSourceFromBytes).
- Whole files and blocks are **content refs**, with paths relative to `tests/fixtures/golden/`:
  - `{ "file": "media/inputs/x.bin", "size": n, "sha256": "..." }`: the file holds all the bytes.
    `size` can be 0 (an empty file).
  - `{ "size": n, "sha256": "...", "segments": [...] }`: used for large sparse content. Join the
    segments in order. `{ "offset", "size", "file" }` = the bytes of that file;
    `{ "offset", "size", "fill": v }` = `size` bytes that all have the value `v` (a run of 4096 or
    more equal bytes is always stored as a fill). Check `size` and `sha256` (SHA-256 of the
    joined content). Some sources are 2 GiB, so implement the source lazily over the segments and
    never build the whole array.
  - Files are deduplicated by SHA-256, so several cases can point at one file. File names are slugs
    and carry no meaning.
  - Inputs live in `media/inputs/`. Outputs (stripped videos) live in `media/outputs/`.
- **ByteSource arguments** (`source` of `readImageMetadata`, `readVideoMetadata`,
  `planIsoBmffStrip`, `planWebmDateStrip`, `streamStripped`):
  - A content ref becomes a source whose `size` is `ref.size`. `read(offset, length)` returns the
    bytes `[max(0, offset), min(size, max(0, offset) + max(0, length)))`. It never throws: a read
    that crosses the end comes back short, and a read past the end comes back empty. This matches
    `byteSourceFromBytes` (see the `byteSourceFromBytes.*` cases in `strip.json`).
  - `{ "failing": { "size": n, "message": "disk gone" } }` is a source that reports `size` n and
    throws `Error(message)` on every `read`.
- **`readStats`** (only on cases whose TS test asserts read behavior) =
  `{ reads, bytesRequested, largestRead, furthestOffset }`. These count the `read` calls made
  during that case's single call. `bytesRequested` sums the requested `length`s.
  `furthestOffset` = max(`offset + length`) over the requests (requested, not returned). The values
  are the exact TS read pattern; a port that mirrors the algorithm reproduces them. The minimum the
  TS tests require is noted per case below.
- Strings decoded from file bytes (`readAscii`) are Latin-1: one char per byte, NUL included. Regex
  `\d` is ASCII `[0-9]`.

### `media/sniff.json`

`sniffFormat(header)`. `args` = `{ header: hex }`. `result` = `{ kind, mimeType, container }` or
`null`. Covers every case of `test/media/sniff.test.ts`: JPEG, PNG and WebP; RIFF that is not WebP;
the 4 heic brands and 4 heif brands; mif1 as an image; compatible brands checked before MP4 brands;
QuickTime; the 13 MP4 major brands; isom with MP4 compatible brands; WebM; an unknown brand; 4
empty or truncated headers; random bytes with seeds 1–10 (`seededBytes` is a test xorshift; the
bytes are in `header`). It also has the constant `SNIFF_HEADER_BYTES` = 64 and `edge:` cases (a
brand past the 64-byte window, an ftyp size smaller than the brand list, ftyp size 0, a 12-byte
ftyp, case-sensitive brands, a minimal RIFF/WEBP, RIFF cut before `WEBP`).

The algorithm, in this order:

1. `window` = the first 64 bytes.
2. Fixed signatures, in this order: JPEG `FF D8 FF` at 0; PNG `89 50 4E 47 0D 0A 1A 0A` at 0;
   WebP = `RIFF` at 0 **and** `WEBP` at 8; WebM `1A 45 DF A3` at 0. If the window is too short for
   a signature part, that signature does not match. Results: `photo`/`image/jpeg`/`jpeg`,
   `photo`/`image/png`/`png`, `photo`/`image/webp`/`webp`, `video`/`video/webm`/`webm`.
3. ftyp: the window needs at least 12 bytes and `ftyp` at offset 4.
   - `brands` = the major brand (bytes 8..12), then compatible brands from offset 16 in 4-byte steps
     while `offset + 4 <= min(u32be(window, 0), window.length)`. So a box size of 0, or any size
     below 20, gives only the major brand.
   - If any brand is in {heic, heix, heim, heis, hevc, hevx, mif1, msf1}: `photo`, `image/heic`
     when any brand is in {heic, heix, heim, heis}, else `image/heif`, container `heif`.
   - Otherwise the major brand decides: `qt  ` gives `video`/`video/quicktime`/`mov`. A major brand
     in {isom, iso2, iso4, iso5, iso6, mp41, mp42, avc1, `M4V `, dash, 3gp4, 3gp5, 3g2a} gives
     `video`/`video/mp4`/`mp4`. Anything else gives `null`.
   - Brands are compared case-sensitively.

### `media/exif.json`

Two functions plus one constant. It covers every case of `test/media/exif.test.ts`.

- `parseTiffExif(tiff)`: `args` = `{ tiff: contentRef }`. The ref holds the TIFF block, starting
  with `II`/`MM`.
- `readImageMetadata(source, container)`: `args` = `{ source: contentRef | failing, container: "jpeg" | "png" | "webp" }`.
- `constant: exports the ASCII limit`: `EXIF_ASCII_MAX_BYTES` = 64.
- `result` = `ImageMetadata` = `{ dateTimeOriginal, offsetTimeOriginal, gps: { latitude, longitude } | null, orientation, malformed }`.

Case groups:

- **parseTiffExif:** full tags in both byte orders; Santiago (S/W) in both byte orders; no tags;
  (0,0) rejected; latitudes ±91; longitudes ±181; 3 bad dates; 2 bad offsets; a negative offset;
  truncated blocks of 0, 4, 12 and `length-10` bytes; a wrong byte order mark; a wrong magic number;
  an IFD count of 600; self-referencing sub-IFD pointers; a 200,000-byte ASCII value (stored as a
  segmented ref). `edge:` cases cover orientation 0, 1 and 9, a pattern-valid impossible date, a
  64-byte ASCII value vs a 65-byte one (date padded with NULs), and an offset `+05:99`.
- **readImageMetadata:** a failing source for each container; JPEG (APP0+Exif, XMP before Exif, no
  EXIF, APP1 after SOS, cut at 60 bytes, random bytes, Exif past the 256 KiB scan limit); PNG
  (eXIf, none, cut); WebP (EXIF, `Exif\0\0` prefix, none); and `edge:` cases (an empty file, a
  restart marker, a broken TIFF inside Exif, a segment length below 2, a wrong PNG signature, a
  wrong WebP form type, a JPEG read as PNG).
- **Scan limit:** `readImageMetadata: never reads beyond the scan limit ...` has
  `readStats` = `{ reads: 1, bytesRequested: 262144, largestRead: 262144, furthestOffset: 262144 }`.
  The TS test requires `furthestOffset <= 262144`.

The TS behavior to mirror:

- **`readImageMetadata`**
  - It makes exactly one read: `read(0, min(size, 262144))`. Then
    `limited = size > window.length`.
  - The locator for `container` finds the TIFF bytes. `null` gives all fields null with
    `malformed: false`; otherwise the result is `parseTiffExif(tiff)`.
  - On any exception, all fields are null and
    `malformed = !(error is MediaRangeError && limited)`. So a structure or range error while the
    window is cut by the limit gives `malformed: false`. Any other error, including a failing read,
    gives `true`.
  - Both kinds of structure failure are `MediaRangeError`: "The file ends before the expected
    data." and "The file structure is not valid."
- **JPEG locator**
  - Bytes 0..2 must be `FF D8`.
  - Loop while `pos < length`: the byte at `pos` must be `FF`, then read the marker.
    - `DA` (SOS) or `D9` (EOI): return `null`.
    - `01` (TEM) or `D0`–`D7` (RST): `pos += 2`.
    - Otherwise: `len` = u16be at `pos+2`, and `len < 2` is an error. The payload is
      `[pos+4, pos+2+len)` and must be in range. An APP1 (`E1`) whose payload starts with
      `Exif\0\0` returns the payload after those 6 bytes. Then `pos = pos + 2 + len`.
  - Reaching the end gives `null`. Fill bytes (`FF FF`) get no special handling.
- **PNG locator**
  - The 8-byte signature must match.
  - Chunks from offset 8: `len` = u32be, then the type (4 Latin-1 chars). The data
    `[pos+8, pos+8+len)` must be in range. `eXIf` returns the data. `IEND` returns `null`.
  - Then `pos = dataEnd + 4`. The CRC is not checked.
- **WebP locator**
  - Needs `RIFF` at 0 and `WEBP` at 8.
  - Chunks from offset 12: fourcc, `size` = u32 **little-endian** at +4, data
    `[pos+8, pos+8+size)` in range.
  - `EXIF` returns the data, with a leading `Exif\0\0` removed if present.
  - Then `pos = dataEnd + size % 2`.
- **`parseTiffExif`**
  - It never throws. On an exception it returns the fields found **so far** with
    `malformed: true`. The fields are filled in this order: orientation, then dateTimeOriginal,
    then offsetTimeOriginal, then gps (see the `length 200` truncation case: date, offset and
    orientation are set, gps is null, malformed is true).
  - The header: `II` (little-endian) or `MM` (big-endian), else malformed. The u16 magic at 2 must
    be 42. IFD0 is at the u32 at 4.
  - An IFD: the count is a u16. Entries are `min(count, 512)` × 12 bytes, all read up front, so an
    entry outside the data throws (the count-600 case). Each entry is tag u16, type u16,
    count u32, value field at +8.
  - A `visited` set of IFD offsets makes a revisited IFD read as empty (self-referencing pointers
    terminate).
  - Pointers in IFD0: Exif `0x8769` and GPS `0x8825` are used only when the entry type is
    LONG (4). The value is a u32.
  - Orientation (`0x0112`, IFD0): the type must be SHORT (3). It is a u16 at the value field, kept
    only if it is 1..8, else `null`.
  - ASCII values (`0x9003` DateTimeOriginal and `0x9011` OffsetTimeOriginal in the Exif IFD; GPS
    refs `1` and `3`) need type 2 and `count <= 64`, else `null`. The data is at the value field
    when `count <= 4`, else at the u32 offset it holds. Read `count` bytes as Latin-1 and remove
    only the trailing run of NULs (`/\u0000+$/`).
  - The date is kept only if it matches `^\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}$`, so impossible
    values like `2026:13:45 99:99:99` pass. The offset is kept only if it matches
    `^([+-])(\d{2}):(\d{2})$`, so `+05:99` passes.
  - GPS (tags `2` latitude and `4` longitude):
    - The type must be RATIONAL (5) with count exactly 3. The data is always at the u32 offset.
    - Each part is `num / den`, as u32 values, and a zero denominator gives NaN. The value is
      `deg + min / 60 + sec / 3600`, evaluated in exactly that order in doubles.
    - The refs: latitude `N` keeps the sign and `S` negates it; longitude `E` / `W` the same way.
      Any other ref, or a missing value, gives `null`.
    - The point is kept only if both values are finite, `|lat| <= 90`, `|lon| <= 180`, and not
      both exactly 0.

### `media/isobmff.json`

Covers every case of `test/media/iso-bmff.test.ts`.

- `readVideoMetadata(source)`: `args` = `{ source: contentRef | failing }`. `result` =
  `{ creationUtc, location: { latitude, longitude } | null, rotationDegrees: 0 | 90 | 180 | 270, malformed }`.
- `parseIso6709(text)`: `args` = `{ text }`. `result` = `{ latitude, longitude }` or `null`.
- Constants `KEYS_NAME_MAX_BYTES` = 256 and `ISO6709_MAX_BYTES` = 64.

Groups:

- Creation time: mvhd v0/v1, 0, moov after mdat, a 50 MiB mdat, a 64-bit size, size 0 to the end,
  10 free boxes, 70 free boxes.
- Location: ©xyz with and without altitude, Apple keys/ilst with and without the full-box header,
  Apple metadata without the location key, non-ISO ©xyz.
- Malformed input: an oversized moov, a moov past EOF, a size below the header size, random bytes,
  an empty file.
- Hardening: a 2,000,000-char Apple key (segmented ref), ©xyz and Apple text over 64 chars, a
  failing source, year > 9999.
- Rotation: 0/90/180/270, a v1 tkhd, the first video track after an audio track, a slanted
  matrix, no video track.
- `parseIso6709`: the 5 accepted rows and the 6 refused rows.
- `edge:` cases, for example 1904 dates, the first location winning, a moov with size 0, a broken
  child after mvhd (`creationUtc` set **and** `malformed: true`), and the parseIso6709 trim, NUL and
  length rules.

Three cases carry `readStats`:

- **50 MiB mdat:** `{ reads: 4, bytesRequested: 156, largestRead: 108, furthestOffset: 52428944 }`.
  The TS test requires `bytesRequested < 4096` and `largestRead <= max(16, moov size)`.
- **64-bit box size:** recorded, but not asserted by the TS test.
- **Oversized moov:** `{ reads: 2, bytesRequested: 32, largestRead: 16, ... }`. The TS test requires
  `largestRead <= 16`.

The TS behavior to mirror:

- **The walk**
  - `offset = 0`. Up to 64 iterations while `offset < size`: `peek = read(offset, 16)`, then parse
    the header with `available = size - offset`.
    - `peek.length < 8` throws.
    - `size32` = u32be at 0, `type` = the 4 Latin-1 bytes at 4. `size32 == 1` reads u64be at 8,
      which needs 16 peek bytes, else it throws. `size32 == 0` means `available`.
    - `size < headerSize` (8 or 16) or `size > available` throws.
  - At `moov`: if `size > 8388608`, throw without reading the body. Otherwise read the body
    `read(offset + header, size - header)`, which must return the full length or it throws. Parse
    it and stop.
  - Anything else: `offset += size`. If the walk ends without a moov, all fields are empty and
    `malformed: false` (the 70-free-box case: moov is box 72). An empty file makes no reads.
  - Any throw anywhere sets `malformed: true` and keeps the fields set before it.
  - u64 values above 2^53−1 throw.
- **moov children**
  - Children are parsed with the same header rules, where `available` = the bytes left in the
    parent and the peek is up to 16 bytes of the parent.
  - `mvhd`: `creationUtc` = creation time. A later mvhd overwrites an earlier one.
  - `trak`: only until the first video track is found.
    - In the trak, `tkhd` gives the rotation, and `mdia` gives the handler = bytes 8..12 of the
      payload of mdia's first `hdlr` child.
    - Handler `vide` makes it the video track, with rotation = the tkhd rotation, or 0 without a
      tkhd. Any other handler means it is not a video track.
  - `udta`: each `©xyz` child (type bytes `A9 78 79 7A`) runs `location ??= xyz(...)`.
  - `meta`: `location ??= apple(...)`.
  - Because of `??=`, a later location box is not even parsed once a location is set. Children
    are handled in file order.
- **Creation time**
  - Payload byte 0 = the version. Version 1 reads u64 at 4, otherwise u32 at 4.
  - 0 gives `null`. Otherwise `ms = (seconds - 2082844800) * 1000`.
  - An invalid date, or a UTC year > 9999, gives `null`. Otherwise the result is
    `toISOString()` = `YYYY-MM-DDTHH:mm:ss.sssZ`. 1904 dates are fine.
- **©xyz**
  - `len` = u16be at 0, and `len > 64` gives `null`.
  - The text = Latin-1 of `min(len, payload.length - 4)` bytes from offset 4, then
    `parseIso6709`.
- **Apple meta**
  - If the payload has at least 4 bytes and u32be(0) == 0, skip 4 bytes (full-box header).
  - Take the first `keys` child and the first `ilst` child; if either is missing, `null`.
  - keys: the count is a u32 at 4, and entries start at 8. While `i < count && pos < len`:
    `size` = u32be, and `size < 8` throws. The name has `size - 8` bytes and is `""` when longer
    than 256 bytes (it is not read). `pos += size`.
  - ilst: each item's box type, read as a u32, is a 1-based key index. If
    `names[index-1] == "com.apple.quicktime.location.ISO6709"`, find the first `data` child with
    payload ≥ 8 bytes. Its text = Latin-1 from offset 8, or `null` if longer than 64 bytes. The
    first non-null `parseIso6709` result wins.
- **Rotation**
  - The matrix starts at payload offset 52 when the tkhd version byte is 1, and at 40 for any
    other version.
  - Cells a@+0, b@+4, c@+12, d@+16 are signed i32 big-endian.
  - Exact matches: `[65536,0,0,65536]` = 0, `[0,65536,-65536,0]` = 90,
    `[-65536,0,0,-65536]` = 180, `[0,-65536,65536,0]` = 270. Anything else = 0.
- **`parseIso6709`**
  1. `text.length > 64` gives `null`. This is checked **before** trimming (see the 65-char case
     with leading spaces).
  2. Remove trailing NULs, then JS `trim()`.
     - JS trim removes `\t \n \v \f \r`, space, U+00A0, U+FEFF, U+1680, U+2000–U+200A, U+2028,
       U+2029, U+202F, U+205F and U+3000.
     - It does **not** remove U+001C–U+001F, which Java's `isWhitespace` does. Use an explicit
       set.
  3. Match `^([+-]\d{2}(?:\.\d+)?)([+-]\d{3}(?:\.\d+)?)(?:[+-]\d+(?:\.\d+)?)?\/?$`.
  4. `parseFloat` both groups (`+002.2945` → 2.2945; `-00.0000` → -0, which is written as `0`).
  5. `|lat| > 90` or `|lon| > 180` gives `null`.

### `media/capture-time.json`

Covers every case of `test/media/capture-time.test.ts`: the 9 `resolveCaptureTime` cases, the
offset limits (2 accepted, 4 out of range), the 2 `earliestTime` cases, and `deviceOffsetMinutes`
(both asserts, plus 0). It also has `edge:` cases.

- **`resolveCaptureTime(input)`**: `args` = `{ input: { exifDateTimeOriginal, exifOffset, containerCreationUtc, fileModifiedUtc, nowUtc, deviceOffsetMinutes } }`.
  All six keys are always present; an absent value is `null`. `result` =
  `{ capturedAtUtc, utcOffsetMinutes, source }`.
  1. EXIF: `exifDateTimeOriginal` must match `^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$`.
     - It is validated by a round trip through JS `Date.UTC(y, m-1, d, h, mi, s)`.
     - Quirk: `Date.UTC` maps years 0–99 to 1900–1999, so EXIF years `0000`–`0099` fail the round
       trip and are treated as missing.
     - February 30, hour 24 and second 60 also fail.
     - The offset is `parseOffsetMinutes(exifOffset)`, falling back to `deviceOffsetMinutes` when
       it is null. `parseOffsetMinutes` matches `^([+-])(\d{2}):(\d{2})$`; minutes ≥ 60 give
       null; the value is `h*60+m`, negated for `-` (`-00:00` → 0); outside [−840, 840] gives
       null.
     - `capturedAtUtc = toISOString(localMs - offset*60000)`. Years above 9999 print in the
       expanded form `+010000-01-01T00:30:00.000Z`; year 100 prints `0100-...`.
     - `source: "exif"`.
  2. Otherwise, `containerCreationUtc` normalized:
     - `Date.parse` → `toISOString()`, giving `"container"` with `deviceOffsetMinutes`.
     - The forms in the goldens: `...T08:00:00.000Z`, `...T08:00:00Z`, `...T10:00:00+02:00`
       (converted to UTC), the date-only `2026-05-01` (**UTC** midnight), and `garbage`
       (unparsable).
     - Kotlin must accept at least these forms. Do not rely on other `Date.parse` formats: a
       date-time without an offset is local time in JS and does not appear in the goldens.
  3. Otherwise `fileModifiedUtc`, the same way, giving `"file"`.
  4. Otherwise `nowUtc` **verbatim** (not normalized) with `"now"`.
- **`earliestTime(times)`**: `args` = `{ times: [ResolvedTime...] }`. It picks the minimum
  `Date.parse(capturedAtUtc)` with strict `<`, so on a tie the earlier item wins. An empty list
  gives `{ error: "RangeError", message: "At least one capture time is required." }`.
- **`deviceOffsetMinutes(date)`**: `args` = `{ timezoneOffsetMinutes }`. TS passes a `Date` stub
  whose `getTimezoneOffset()` returns that value (JS sign: minutes **behind** UTC, so UTC+2 is
  −120). `result = 0 - value` (120, −300, 0). In Kotlin this is the platform's UTC offset in
  minutes east of UTC.

### `media/geometry.json`

Covers every case of `test/media/geometry.test.ts`: the 9 `fitWithin` rows, never upscaling,
orientations 1–4 and 5–8, and `null`. It also has `edge:` cases (exact .5 rounding, 2.5 → 3, a
product just under .5, one pixel over the edge, orientations 0 and 9).

- `fitWithin(width, height, maxEdge)`: `args` = `{ width, height, maxEdge }`.
  `scale = longest > maxEdge ? maxEdge / longest : 1`. Each side is
  `max(1, Math.round(side * scale))`. JS `Math.round` rounds .5 **up** (2.5 → 3); do not use
  banker's rounding.
- `displaySize(width, height, orientation)`: `args` = `{ width, height, orientation | null }`.
  It swaps width and height only for orientations 5..8.
- `result` = `{ width, height }`.

### `media/strip.json`

Covers every case of `test/media/strip.test.ts` and `test/media/byte-source.test.ts`. The
byte-source cases live here as `byteSourceFromBytes.*`.

**`planIsoBmffStrip(source)`**

- `args` = `{ source: contentRef }`. `result` = `StripPlan` =
  `{ segments: [{ kind: "bytes", bytes: hex } | { kind: "copy", offset, length }], outputSize, moov: hex }`,
  or `{ error: "UnsupportedVideoError", message }`.
- The messages: `too many boxes`, `truncated box`, `bad box size`, `fragmented MP4`, `missing moov`,
  `moov too large`, `truncated child`, `bad child size`, `bad chunk table`,
  `chunk offset overflow`, `chunk offset outside mdat`.

The algorithm:

1. **Top-level boxes**
   - While `offset < size`: if more than 64 boxes have already been collected, `too many boxes`
     (so 65 boxes are fine).
   - `peek = read(offset, 16)`; fewer than 8 bytes is `truncated box`.
   - `size32 == 1` reads u64be at 8. If that is out of range, it throws `MediaRangeError`, not
     `UnsupportedVideoError`.
   - `size32 == 0` means to the end.
   - `size < 8` or `offset + size > source.size` is `bad box size`. A large size is checked
     against 8, not 16.
2. Any `moof` is `fragmented MP4`.
3. Kept = the boxes with type in {ftyp, moov, mdat, free, skip, wide}, in file order. Every other
   top-level box is dropped (uuid, udta, meta, unknown types).
4. There must be exactly one kept `moov`, else `missing moov`. Its size above 8388608 is
   `moov too large`, checked before the body is read.
5. **Rewriting the moov**
   - Read the whole moov box and rewrite it recursively.
   - Removed: {udta, meta, uuid}.
   - Containers, which are re-encoded from their rewritten children: {moov, trak, mdia, minf,
     stbl, edts, dinf, mvex}.
   - Timed boxes {mvhd, tkhd, mdhd}: zero payload bytes `[4, 4 + 2*w)`, where `w` = 8 if payload
     byte 0 == 1, else 4.
   - `stco` (u32 entries) and `co64` (u64 entries): the count is a u32 at payload offset 4, and
     entries start at 8. If `8 + count*w > payload length`, `bad chunk table`. Each entry is mapped
     (see below).
   - Everything else: the payload is copied unchanged.
   - **Every written box gets a plain 8-byte header**: `u32be(total size)` + type. 64-bit and
     size-0 headers are rewritten that way.
   - `©xyz` directly under moov is **not** removed (it is not in the removed set), although
     `moovHasMetadata` reports it.
   - Child parsing: fewer than 8 bytes left is `truncated child`; size 1 reads u64 at +8; size 0
     means to the end of the parent; `size < header` or past the parent is `bad child size`.
6. **Chunk offset mapping**
   - First the rewrite runs with the identity mapping, to learn the new moov size. `newStart` of
     each kept box is then the running sum of the kept sizes, with the moov counted at its
     rewritten size.
   - Then it rewrites again with the real mapping: find the first kept `mdat` with
     `box.offset <= v < box.offset + box.size` (the box range includes its header). None found is
     `chunk offset outside mdat`.
   - The new value is `v + newStart(mdat) - mdat.offset`. For `stco`, a value above 0xFFFFFFFF or
     below 0 is `chunk offset overflow`.
7. **Segments, per kept box in order:** moov → `bytes` (the rewritten moov, also in
   `result.moov`); mdat → `copy { offset: box.offset, length: box.size }` (the whole box, header
   included); others → `bytes` read verbatim from the source. `outputSize` = the sum of the sizes.

**`streamStripped(source, plan, chunkBytes)`**

- `args` = `{ source: contentRef, plan: { segments }, chunkBytes }`. `plan.segments` uses the same
  encoding as the plan result, and the plan is exactly what the preceding `planIsoBmffStrip` /
  `planWebmDateStrip` case returned.
- It yields each non-empty `bytes` segment whole. Each `copy` is yielded as
  `read(offset + done, min(chunkBytes, length - done))` for `done = 0, chunkBytes, ...`.
- `result` = `{ output: contentRef (media/outputs/...), chunkCount, largestChunk }`, where
  `output` is all yielded chunks joined. Compare `size` and `sha256`, and compare the bytes for the
  file refs.
- Most cases use `chunkBytes` = 1048576 (`STRIP_COPY_CHUNK_BYTES`). The size-0 mdat edge case uses
  1000.

**Other functions**

- `moovHasMetadata(moov)`: `args` = `{ moov: hex }`, the whole moov box including its header.
  Skip the first 8 bytes (always 8) and walk the children. It returns `true` if any child type is
  in {udta, meta, uuid, ©xyz}, recursing into the container types above. Malformed children throw
  as in step 5.
- `readVideoMetadata(source)` on a stripped output: `args` = `{ source: outputRef }`. It checks
  that creation time and location are gone.
- **`planWebmDateStrip(source)`**: `args` = `{ source: contentRef }`. `result` = a plan whose
  `moov` is `""`.
  - If DateUTC is found at data offset `at`, the segments are `copy [0, at)`, 8 zero bytes as
    `bytes`, and `copy [at+8, size)`. Otherwise there is one `copy { 0, size }` (also for an empty
    file, which streams nothing). `outputSize` = `size`.
  - The search starts with `offset = 0, end = size` and loops while `offset < end`:
    - `h = readEbmlHeader(read(offset, 12))`; null ends the search with nothing found.
    - The ID length = the leading zero bits of the first byte + 1. A first byte of 0 or a length
      > 4 is null, and so is having fewer than `idLen + 1` bytes. The ID is the big-endian value
      including the marker bits (e.g. `0x18538067`).
    - The size length is found the same way from the next byte. A first byte of 0 or a length > 8
      is null; so is having too few bytes.
    - The size value = `first & (0xFF >> sizeLen)` followed by the next bytes. The size is unknown
      when all value bits are 1.
    - If not yet inside the Segment and `id == 0x18538067`: go inside, `offset = dataStart`, and
      set `end = min(end, dataStart + size)` when the size is known.
    - If inside the Segment, not yet inside Info, `id == 0x1549A966` and the size is known: go
      inside, `offset = dataStart`, `end = dataStart + size`.
    - If inside Info and `id == 0x4461`: found if `size == 8`, otherwise the search ends with
      nothing found.
    - If `id == 0x1F43B675` (Cluster) or the size is unknown: nothing found.
    - Otherwise `offset = dataStart + size`.
- `hasExifSegment(bytes)`: `args` = `{ bytes: contentRef }`. It runs the JPEG locator (see
  exif.json) over all the bytes. Exif found → `true`; SOS, EOI or the end reached first →
  `false`; **any error → `true`** (fail closed: random bytes and the empty file give `true`).
- `byteSourceFromBytes.size`: `args` = `{ bytes: hex }`, result = the length.
- `byteSourceFromBytes.read`: `args` = `{ bytes: hex, offset, length }`, result = the returned
  bytes as hex. `start = max(0, offset)`, the result is `bytes[start, min(len, start + max(0, length)))`.
  A negative offset reads from 0 and a negative length reads nothing.

**Cases**

- The 8 layout / version / table combinations each produce five cases:
  `planIsoBmffStrip: <name>`, `streamStripped: <name>`, `moovHasMetadata: <name>: stripped moov`
  (`false`), `readVideoMetadata: <name>: stripped output` (no creation time, no location), and
  `moovHasMetadata: <name>: original moov` (`true`).
  - The input has top-level `uuid` + `udta` junk, and the moov holds udta (©xyz), Apple meta,
    uuid, a trak-level udta, and timed boxes.
  - The chunk-table check of the TS test (offsets still point at the same bytes) is implied by
    the exact output bytes.
- `rejects fragmented MP4 (moof)` and `rejects a chunk offset that points outside every mdat` give
  errors.
- `streams a 2 GiB mdat ...`
  - The input is a segmented ref of 2147483818 bytes. The plan case has
    `readStats` = `{ reads: 5, bytesRequested: 218, largestRead: 154, furthestOffset: 186 }`.
  - The stream case has `readStats` =
    `{ reads: 2048, bytesRequested: 2147483648, largestRead: 1048576, ... }`, a segmented output of
    2147483780 bytes, and `chunkCount: 2050`.
  - The TS test requires every chunk and every read to be ≤ 1048576 bytes, and the total to equal
    `outputSize`.
- `edge:` ISO cases: no moov, two moovs, more than 64 boxes, a truncated header, a box past EOF, a
  moov over the limit, a bad chunk table, unknown top-level boxes dropped while free/skip/wide are
  kept, a size-0 mdat streamed in 1000-byte chunks.
- WebM: DateUTC zeroed, no DateUTC; `edge:` cases for an unknown-size Segment, a 4-byte DateUTC,
  Cluster before Info, an empty file, a leading zero byte.
- `moovHasMetadata` edges, the `hasExifSegment` cases, and the `byteSourceFromBytes` size and read cases plus edges.

### `index/tokenize.json`

`fn` = `tokenize`, `args` = `{ "text": string }`, `result` = the token array (`tokenize(text)`).
Cases (23): the eight `it.each` rows of `test/index/tokenize.test.ts` (named `tokenize: "<text>"`),
`drops tokens shorter than TOKEN_MIN_CHARS`, `keeps at most MAX_INDEX_TOKENS_PER_ENTRY tokens, ...`
(520 words `w0 ... w519`), `deduplicates before applying the cap`, and `edge:` cases (cap reached
exactly, NFKC ligatures / superscripts / roman numerals / circled digits, Greek final sigma, `İ` and
`ẞ`, non-composing combining marks, Devanagari, a supplementary-plane letter, emoji / apostrophe /
underscore / dot separators, Unicode whitespace and U+200B, Arabic-Indic and fullwidth digits,
Arabic diacritics, separators only).

Algorithm the Kotlin port must reproduce exactly:

1. `text.normalize("NFKC")` (Kotlin: `java.text.Normalizer.normalize(text, Normalizer.Form.NFKC)`).
2. `.toLowerCase()`: JS full Unicode lowercasing, locale-independent (Kotlin: `lowercase()`, which
   uses `Locale.ROOT`; never the default locale). It is context-sensitive for the Greek final sigma
   (`"ΟΔΟΣ"` -> `"οδος"`, `"ΣΑΣ"` -> `"σας"`), maps `İ` (U+0130) to `i` + U+0307 (two code units)
   and `ẞ` (U+1E9E) to `ß`. Lowercasing happens after NFKC, so no renormalization: `İ` yields
   `i` + U+0307 and U+0307 then acts as a separator (`"İSTANBUL"` -> `"stanbul"`).
3. Split on the regex `/[^\p{L}\p{N}]+/u`: every code point that is not a Unicode Letter (any `L*`
   category) or Number (`Nd`, `Nl`, `No`) separates tokens. Marks (`Mn`/`Mc`/`Me`) separate too: a
   combining accent that NFKC cannot compose (`"x\u0301yz"`), Devanagari vowel signs and viramas,
   Arabic harakat. Emoji, `_`, `'`, `.`, `-`, U+200B, NBSP and all other whitespace separate.
   Kotlin: iterate by code point and test `Character.getType` (letters: UPPERCASE_LETTER,
   LOWERCASE_LETTER, TITLECASE_LETTER, MODIFIER_LETTER, OTHER_LETTER; numbers: DECIMAL_DIGIT_NUMBER,
   LETTER_NUMBER, OTHER_NUMBER), or `Regex("[^\\p{L}\\p{N}]+")` (Java's `\p{L}`/`\p{N}` are the same
   general categories). Unicode tables of the generator: Node 26.7.0, Unicode 17.0, ICU 78.3; the cases
   use only long-assigned characters.
4. In split order, skip parts whose **UTF-16 length** (`String.length`, code units) is below 2
   (`TOKEN_MIN_CHARS`). Empty parts (leading/trailing separators) are skipped by this rule. A single
   supplementary-plane letter such as `𠀀` (U+20000) has length 2 and is kept; `"a"` is dropped.
5. Deduplicate keeping first occurrence order (an insertion-ordered set: `LinkedHashSet`), and stop
   as soon as the set holds 500 (`MAX_INDEX_TOKENS_PER_ENTRY`) tokens. Duplicates do not count
   toward the cap.

### `index/gallery-query.json` + `index/inputs/synthetic-entries.json`

Three `fn` families: `GalleryIndex.<method>` (stateful index, 50 cases), `sortEntryIds` (19 cases),
`attributesMatch` (11 `edge:` cases); 80 cases in total. Covers every `it`/row of `test/index/gallery-index.test.ts`
(except the on-this-day tests, which are in `index/on-this-day.json`) and
`test/index/gallery-refine.test.ts`. Case names are `<fn>: <TS test title>` (with `(...)` suffixes when
one TS test is split, and `%j`/`%s` rows written out, e.g. `GalleryIndex.query: tags ["a","b"] are
AND-combined`, `GalleryIndex.query: date range 2026-11-08..null is inclusive`,
`GalleryIndex.query: text "bea sun" prefix-matches every query token`).

#### `IndexedEntry` JSON

Every entry is given in full: `id`, `isOwn`, `collectionIds`, `trashed`, `unreadable`, `tags`,
`coverMediaId` (string or `null`), `mediaCount`, `hasVideo`, `notePreview`, `pending`, `takenAtUtc`,
`utcOffsetMinutes`, `localDate` (`YYYY-MM-DD`), `monthDay` (`MM-DD`), and optionally `favorite`,
`videoCount`, `videoMs`, `originalBytes` (absent keys mean TS `undefined`; `favorite` absent is not
`true`). `localDate`/`monthDay` were precomputed by the generator with `localDateOf(takenAtUtc,
utcOffsetMinutes)` (see `domain/entry-time.json`); **the index never recomputes them**, it uses the
stored strings as they are. Tags are stored as given, the index does not normalize them.

#### Driving protocol for `GalleryIndex.*`

```
args = {
  "homeCollectionId": string,          // createGalleryIndex({ homeCollectionId })
  "setupFile": { file, size, sha256 }, // optional; a JSON file holding an array of steps
  "setup": [step, ...],                // steps run after setupFile, results ignored
  "calls": [step, ...]                 // steps whose results are recorded
}
step   = { "method": string, "args": [ ...positional arguments of that method... ] }
result = [ one element per call, in order ]
```

1. Create a fresh index with `homeCollectionId`.
2. If `setupFile` is present, read `tests/fixtures/golden/<file>` (sha256 given), parse it as a JSON
   array of steps and run them in order. Only `index/inputs/synthetic-entries.json` exists: the 50
   synthetic entries of the TS test (`e00`..`e49`, upserted in that order with their note text).
   Cases with `setupFile` are the ones the TS test builds with `filled()`.
3. Run `setup` in order, then `calls` in order. Calls can mutate too (the `version` and on-this-day
   removal cases interleave mutations and reads); results of mutating methods are `null`.

| `method` | `args` | recorded result |
|---|---|---|
| `upsert` | `[entry, noteText]` | `null` |
| `remove` | `[entryId]` | `null` |
| `clear` | `[]` | `null` |
| `get` | `[entryId]` | the stored entry object exactly as upserted (same keys), or `null` |
| `size` | `[]` | number of entries (trashed and unreadable included) |
| `version` | `[]` | number |
| `query` | `[filter]` | array of `{ localDate, entryIds }` sections |
| `allTags` | `[]` | array of `{ tag, count }` |
| `tagSuggestions` | `[prefix, limit]` | array of tags |
| `onThisDay` | `[todayLocalDate, scope]` | array of `{ yearsAgo, entryIds }` |
| `daysWithEntries` | `[yearMonth, scope]` | the `Map` as an array of `[localDate, entryId]` pairs **in Map insertion order** (not sorted) |

`fn` names the method the case is about; the driver is the same for every `GalleryIndex.*` case.
`filter` = `{ scope, view: "journal"|"trash", tags: string[], fromDate: string|null,
toDate: string|null, text: string, attributes? }`; `attributes` absent = TS `undefined` (no attribute
filtering); inside `attributes` an absent key equals `"off"`. `scope` = `{ "kind": "home" }` or
`{ "kind": "collection", "collectionId": string }`.

#### Index semantics to mimic

- **Instant**: `takenAtMs = Date.parse(takenAtUtc)`. Inputs are ISO 8601 with `Z` or `+hh:mm`, with or
  without fractional seconds (`...T10:00:00Z`, `...T10:00:00.500Z`, `...T13:30:00+02:00`). Kotlin:
  `Instant.parse` / `OffsetDateTime.parse(...).toInstant()` to epoch milliseconds.
- **Master order** (used by `query`, `daysWithEntries`, and inside each `onThisDay` group):
  `takenAtMs` descending, ties by `id` ascending in **UTF-16 code-unit order** (JS `<` on strings,
  equal to Kotlin `String.compareTo`; not code-point order, not `localeCompare`). Example:
  `B < a10 < a9 < b < z < ä < 🌊 < ａ` (U+D83C surrogate sorts before U+FF41). The TS test's helper
  uses `localeCompare` only for its own expectation; the index does not.
- **`upsert(entry, noteText)`**: first removes any entry with the same id (old tags, tokens, month-day
  all dropped), then stores the new one. If `unreadable`, it gets no tags and no tokens (not found by
  `tags`/`text` queries, not counted), but it is still listed by unfiltered queries,
  `daysWithEntries` and `onThisDay`. Tags are deduplicated (a set). Tokens = `tokenize(noteText)` (see
  `index/tokenize.json`); `notePreview` is not tokenized. A tag is **counted** (for `allTags` /
  `tagSuggestions`) only if `isOwn && !trashed && !unreadable`; trashed or foreign entries are still
  found by `tags` queries.
- **`version`**: starts at 0; `+1` on every `upsert` (even when replacing), `+1` on `remove` only if the
  id existed, `+1` on every `clear` (even when empty).
- **`query(filter)`**:
  1. Candidate set: for each `filter.tags` element the set of entry ids that have exactly that tag
     (string equality, no normalization: `Beach` does not match `beach`, `bea` does not prefix-match);
     for each token of `tokenize(filter.text)` the ids having at least one stored token that
     `startsWith` the query token. All sets are intersected (AND). If there are no tags and no query
     tokens (text empty, whitespace-only, or only 1-char words like `"x"`), there is no candidate
     restriction at all.
  2. Walk all entries in master order; keep an entry if it is a candidate, in scope (`collectionIds`
     contains `homeCollectionId` for `home`, else `scope.collectionId`), passes `attributesMatch`
     (when `attributes` is present), has `trashed == (view == "trash")`, and
     `fromDate <= localDate <= toDate` where the bounds are compared **as plain strings** (UTF-16
     order; a `null` bound is open; malformed bounds such as `2026-11-5` are still compared as
     strings).
  3. Group consecutive kept entries with equal `localDate` into a section. Grouping is by
     adjacency, not by date: with mixed offsets the same `localDate` can appear in two sections
     (`edge: sections follow time order...`).
- **`attributesMatch(entry, filters, homeCollectionId)`** checks keys in the order video, note,
  multiple, shared, pending, favorite; `"off"`/absent = ignore; `"include"` requires the attribute,
  `"exclude"` requires its absence. Attributes: `video` = `hasVideo`; `note` = `notePreview != ""`
  (a single space counts); `multiple` = `mediaCount > 1`; `shared` = `!isOwn ||` some collection id
  `!= homeCollectionId`; `pending` = `pending`; `favorite` = `favorite == true`.
- **`allTags()`**: counted tags with count > 0, sorted by tag in UTF-16 code-unit order (`Zoo < a <
  zoo < été < 日本 < 🌊 < ａ`).
- **`tagSuggestions(prefix, limit)`**: if `prefix.trim()` is empty (JS `trim()` whitespace set:
  Unicode White_Space plus U+FEFF) the prefix is `""` (matches all); otherwise
  `normalizeTag(prefix)` = NFKC, `trim()`, every run of `\s` (JS Unicode whitespace) replaced by one
  space, `toLowerCase()`; if that is longer than 64 UTF-16 units the result is `[]`. Then counted tags
  whose text `startsWith` it, sorted by count descending, ties by tag in UTF-16 order, first `limit`
  (`limit` 0 -> `[]`). Stored tags are compared raw (a stored `ﬁ` is not matched by `fi`).
- **`daysWithEntries(yearMonth, scope)`**: walk entries in master order, skip trashed, entries whose
  `localDate` does not start with `yearMonth + "-"` (so `2026-1` matches nothing in November), and
  out-of-scope entries; the first entry seen for each `localDate` wins (= newest, ties by smallest id).
  The result keeps insertion order (Kotlin `LinkedHashMap`), i.e. in order of the winning entries'
  instants, newest first. Unreadable entries are included.
- **`onThisDay`**: see `index/on-this-day.json`.

#### `sortEntryIds`

`args` = `{ ids: string[], entries: IndexedEntry[], sort: { key, descending }, seed: number }`; call
`sortEntryIds(ids, get, sort, seed)` with `get(id)` = the element of `entries` with that `id`, or
`null` if none. `result` = the returned id array. For `random` cases `entries` is `[]` (the shuffle
never calls `get`; the TS test's lookup is irrelevant there).

- Non-random keys: measure = `notePreview.length` (UTF-16 units: `🌸` = 2, `é` = 1, `e\u0301` = 2) for
  `noteLength`, `mediaCount` for `mediaCount`, and the **raw `takenAtUtc` string** for `takenAt`
  (string comparison, not instants: `...00Z` sorts after `...00.000Z` because `'.' < 'Z'`). Compare:
  if either id has no entry, the missing one goes last (in both directions); if measures differ,
  `<` order (numbers numerically, strings in UTF-16 order) multiplied by -1 when `descending`; else
  `takenAtUtc` strings descending **regardless of `descending`**; else id ascending (UTF-16). The
  comparator is a total order for these cases, so any stable sort gives the golden result.
- `random`: ignores `descending` and `get`. Fisher-Yates from the end with a 31-bit LCG computed in
  **IEEE-754 doubles** (the product overflows 2^53, so integer/`Long` arithmetic gives different
  numbers; for seed 7 only the first draw agrees):
  ```
  state = abs(trunc(seed)) % 2147483648            // doubles; -7.9 -> 7, 2147483655 -> 7
  next() = { state = (state * 1103515245 + 12345) % 2147483648; return state / 2147483648 }
  for position = n-1 downTo 1: other = floor(next() * (position + 1)); swap(position, other)
  ```
  Kotlin: keep `state` a `Double` and use `*`, `+`, `%` on `Double` (Kotlin's `Double.rem` is the same
  truncated fmod as JS `%`).

### `index/on-this-day.json`

`fn` = `GalleryIndex.onThisDay`, driven exactly like the `GalleryIndex.*` cases of
`index/gallery-query.json` (`homeCollectionId`, `setup`, `calls`; no `setupFile`). Cases (9): the TS
test `on this day: same MM-DD in earlier years, grouped by years ago, newest first` split into
`(home scope)`, `(collection scope)`, `(a day without entries)`; `upsert moves an entry to its new day
and month-day`; and `edge:` cases (leap day, numeric group order and skipped future/same-year
entries, local vs UTC dates, ties / unreadable / pending / foreign entries, removal and clear).

`onThisDay(todayLocalDate, scope)`:

1. `monthDay = todayLocalDate.substring(5)` (`MM-DD`); `todayYear = Number(todayLocalDate[0..4])`.
2. Take the entries whose stored `monthDay` equals it (the stored local `monthDay`, not the UTC date;
   no leap-year fallback: `02-29` only matches `02-29`, and `2027-02-28` does not list `02-29` entries),
   sort them by the master order (instant descending, then id ascending in UTF-16 order).
3. Skip trashed and out-of-scope entries (unreadable, pending and `isOwn == false` entries are kept).
   `yearsAgo = todayYear - Number(entry.localDate[0..4])` (calendar years from the stored local date,
   no day arithmetic); skip `yearsAgo < 1` (same year and future years).
4. Group by `yearsAgo` keeping the order of step 2 inside each group; output groups sorted by
   `yearsAgo` ascending **numerically** (2, 10, 36). An empty result is `[]`.

### Sync files: common rules

The five `sync/*.json` files are function tables over `packages/core/src/sync/*.ts`
(`{ name, fn, args, result }`, extra fields noted per file). Every case name starts with the
`fn` (`"classifyPushFailure: ..."`), followed by the TS test title (`it` text, with a
parenthesized suffix when one `it` holds several assertions or loops over rows, and the
`describe` group name for `mergeMetadata`); `edge:` cases are extra. No case throws; no `result`
is an `{ "error": ... }` object. `args` keys are listed in the call's argument order. Nothing
reads a clock: every time is a fixed number or string in `args`.

Constants (`packages/core/src/sync/constants.ts`): `MAX_ATTEMPTS = 8`, `BACKOFF_BASE_MS = 2000`,
`BACKOFF_CAP_MS = 900000`, `BACKOFF_FACTOR = 2`, `MS_PER_SECOND = 1000`, statuses `401`, `404`,
`429`, `SERVER_ERROR_STATUS_MIN = 500`.

### `sync/failures.json`

`nextAttemptAt` and `classifyPushFailure`: 59 cases (13 + 46).

**Random parameter.** Both functions take `random: () => number`. The case supplies
`args.randomValues` (a list of doubles in `[0, 1]`); the n-th call of `random` returns
`randomValues[n]` (a call past the end would throw, which never happens). The extra case-level
field `randomCalls` is how many values the call consumed (0 or 1). A Kotlin test must check both
the result and that its random was called exactly `randomCalls` times. The TS tests' constant
`HALF = () => 0.5` is recorded as `randomValues: [0.5]`.

**`nextAttemptAt(attempts, now, random)`**, `args = { attempts, now, randomValues }`, `result` = number:

```
result = now + random() * min(900000, 2000 * 2^attempts)
```

All IEEE-754 double arithmetic, in exactly this order: `2^attempts` (JS `**`, exact for integer
exponents; `2^2000 = Infinity`, then `min(900000, Infinity) = 900000`; `2^-1 = 0.5`), times 2000,
`min` with the cap, times the random value, plus `now`. **There is no `Math.floor`/`Math.round`**:
the result is a double and can be fractional (`5000246.913578`, `1700000111111.11`,
`260999.99999999997`), or rounded only by the double addition (`5000000 + 260999.99999999997`
= `5261000`). Port with `Double` (e.g. `now + r * minOf(900000.0, 2000.0 * 2.0.pow(attempts))`),
not with `Long` math. The ceiling reaches the cap at `attempts = 9` (2000·512 = 1024000 > 900000);
`attempts = 8` gives 512000. Cases: the TS rows (attempts 0, 3, random 0, cap at 20) and edges
(8, 9, fractions, huge `now`, `attempts` 2000 and -1, two supplied values of which only one is
consumed).

Numbers in JSON use JS shortest round-trip printing (`5001000`, not `5001000.0`;
`260999.99999999997`). Compare doubles exactly (parse both sides as `Double`).

**`classifyPushFailure(kind, failure, attempts, now, random)`**,
`args = { kind, failure, attempts, now, randomValues }`, `result` = `FailureDecision`:

- `kind`: an `OutboxKind` string (`createEntry`, `updateEntryMeta`, `addMedia`, `removeMedia`,
  `trashEntry`, `restoreEntry`, `deleteEntry`).
- `failure`: a plain `PushFailure` object, never an `Error` instance:
  `{ "kind": "network" }` or `{ "kind": "http", "status": <int>, "code": <ErrorCode string>,
  "retryAfterSeconds": <number | null> }` (`retryAfterSeconds` is always present, `null` = no
  `Retry-After`). `code` is one of the contract `ERROR_CODES`.
- `result`: `{ "action": "retry", "at": <number> }`, or `{ "action": "conflict" | "drop" |
  "dead" | "signedOut" }` (no other keys).

Decision order (first match wins; a Kotlin port must keep this order):

1. `network` → backoff (below).
2. `status == 401` → `signedOut` (even with code `REVISION_CONFLICT`).
3. `code == "REVISION_CONFLICT"` → `conflict` (whatever the status: 409, 500, 404).
4. `status == 404` → `drop` for `updateEntryMeta`, `trashEntry`, `restoreEntry`, `removeMedia`,
   `deleteEntry`; `dead` for `createEntry`, `addMedia` (code ignored).
5. `status == 429` (code ignored) → always `retry`, never `dead` whatever `attempts`:
   `at = now + retryAfterSeconds * 1000` when `retryAfterSeconds` is not null (no random call;
   no rounding: `1.5` → `+1500`; `0` → `now`), else `at = nextAttemptAt(attempts, now, random)`.
6. `status >= 500` or `code == "STORAGE_UNAVAILABLE"` (even with a 4xx status) → backoff.
7. anything else → `dead` (403 quota/feature, 413, 422, 409 `ID_CONFLICT`, 400, 499, ...).

Backoff = compute `at = nextAttemptAt(attempts, now, random)` **first** (consuming one random
value), then return `dead` if `attempts >= 8`, else `{ retry, at }`. So a `dead` from a network or
5xx failure still has `randomCalls: 1`; `signedOut`/`conflict`/`drop`/4xx `dead` and a 429 with
`Retry-After` have `randomCalls: 0`. Unless stated, `now = 5000000` and `randomValues = [0.5]`.

### `sync/push-plan.json`

`planPushBatch(items, inFlightEntryIds, now, capacity)`, 21 cases,
`args = { items, inFlightEntryIds, now, capacity }`, `result` = array of selected item ids.

- `items`: `OutboxItemView[]` in input order, each `{ id: int, kind: OutboxKind,
  entryId: string | null, state: "queued" | "dead", nextAttemptAt: number }`.
- `inFlightEntryIds`: a TS `Set<string>`, encoded as a sorted array (order is irrelevant).
- `now` (default 1000), `capacity` (default 10; cases with 2, 1, 0, -1).

Algorithm: stable-sort a copy of `items` by `id` ascending (JS `Array.prototype.sort` is stable,
so equal ids keep input order; use `sortedBy`, which is stable too). Walk the sorted list with a
set `seen` of entry ids: an item is blocked if its `entryId` is non-null and is in `seen` or in
`inFlightEntryIds`; it is due if `state == "queued"` and `nextAttemptAt <= now` (double compare,
`1000.0000001` is not due at 1000). Select it if not blocked, due and fewer than `capacity` ids
are selected. After that check, **every** item with a non-null `entryId` adds it to `seen`
(also dead, not-due, blocked and over-capacity items), so an earlier unsent item of an entry
blocks all its later items. `null` entry ids never block and are never blocked; the empty string
is a normal entry id. Inputs are not mutated (the TS test "does not mutate its input" is a
regular case here; the non-mutation itself is not observable in the golden).

### `sync/pulled-entry.json`

`decidePulledEntry(local, pulledRevision)`, 14 cases, `args = { local, pulledRevision }`,
`result` = one of `"insert"`, `"overwrite"`, `"updateTrashOnly"`, `"rejectRollback"`.

`local` is `null` (entry absent locally) or `{ localState: "synced" | "pending_create" |
"pending_update", baseRevision: int }`. Rules: `null` → `insert`; `pulledRevision < baseRevision`
→ `rejectRollback` (any state); else `synced` → `overwrite`, pending states → `updateTrashOnly`.

### `sync/collections-plan.json`

`planCollections(remote, local)`, 20 cases, `args = { remote, local }`,
`result = { refetch: string[], pull: string[], resync: string[], drop: string[] }` (all four
keys always present, in this order).

- `remote`: `RemoteCollectionState[]` = `{ id, kind: "home" | "shared" | "link",
  role: "owner" | "viewer", lastSeq: int, tombstonesPrunedThroughSeq: int, metaUpdatedAt: string }`.
- `local`: `LocalCollectionState[]` = `{ id, cursorSeq: int, metaUpdatedAt: string }`.

Algorithm: drop every remote with `kind == "link"` first (they are invisible: never refetched or
pulled, and a local collection whose only remote counterpart is a link is dropped). For each
remaining remote in input order, with `known` = the local of the same id (last one wins on
duplicates) and `cursor = known?.cursorSeq ?? 0`:
- `refetch` if `known` is absent or `known.metaUpdatedAt != remote.metaUpdatedAt`
  (**plain string comparison**: `2026-11-12T10:00:00Z` differs from `2026-11-12T10:00:00.000Z`);
- then, independently of refetch: `resync` if `known` exists and `0 < cursor <
  tombstonesPrunedThroughSeq`; otherwise `pull` if `lastSeq > cursor`. `resync` and `pull` are
  exclusive; an unknown collection is never resynced.

`drop` = ids of `local` (in local order) whose id is not among the non-link remote ids. `role`
never matters.

### `sync/merge.json`

`mergeMetadata` (85 cases) and `metadataEquivalent` (29 cases).

**`mergeMetadata(ancestor, local, server, nowUtc)`**, `args = { ancestor, local, server, nowUtc }`,
`result` = the merged `EntryMetadataV1` object. `ancestor`/`local`/`server` are full
`EntryMetadataV1` JSON objects; an absent optional key (`favorite`, a descriptor's `caption`) is
absent in the JSON (never `null`). The TS test builds each side as `metadata({...ancestorPatch,
...sidePatch})`; the golden stores the three resulting objects. The base fixture: `note "base"`,
`tags ["a","b"]`, `location null`, two photo descriptors `mmmmmmmmmmmmmmmmmmmm01`/`...02`,
`createdAtUtc = editedAtUtc = 2026-01-01T00:00:00.000Z`; `nowUtc` is `2026-04-01T00:00:00.000Z`
unless stated. Merged result key order: `schemaVersion, takenAtUtc, utcOffsetMinutes, timeZone,
note, tags, location, media, createdAtUtc, editedAtUtc[, favorite]`. The result is not validated
(e.g. `media` may be empty).

Merge rules:

- "Later edit" = `Date.parse(server.editedAtUtc) > Date.parse(local.editedAtUtc)` → server, else
  local. Instants are compared, not strings (`2026-03-01T01:00:00+01:00` is earlier than
  `2026-03-01T00:30:00.000Z`; `...00Z` equals `...00.000Z`, a tie). Ties go to local. An
  unparsable value parses to `NaN` and any comparison with `NaN` is false, so the server never
  wins if either edit time is unparsable. Inputs only use `YYYY-MM-DDTHH:mm:ss[.sss](Z|±HH:MM)`
  and `"not a date"`.
- `pickSide(changedLocally, changedOnServer)`: only local changed → local; only server changed →
  server (even if local edited later); neither → local value (equal to ancestor's); both → later
  edit.
- **Time unit** (`takenAtUtc`, `utcOffsetMinutes`, `timeZone` move together): a side "changed"
  if any of the three differs from the ancestor; the whole triple comes from the picked side
  (no per-field mix: local `takenAtUtc`+offset with server `timeZone` never happens).
- **note**: string `!==` against the ancestor, then `pickSide`.
- **location**: deep equality against the ancestor (`null` vs object; objects compared key by
  key over the union of keys, numbers with `===`, so `accuracyMeters null` ≠ `0` and
  `48.85` ≠ `48.850001`), then `pickSide`; the picked value is copied as is.
- **favorite**: each value is reduced to `favorite === true` (absent and `false` are the same);
  `pickSide` on that boolean; output `"favorite": true` when true, key **omitted** when false.
- **tags**: each side's tags go through `normalizeTag` (below), dropping `null`s, into sets
  `B` (ancestor), `L`, `S`. Result = `{t ∈ B : t ∈ L and t ∈ S}` ∪ `{t ∈ L ∪ S : t ∉ B}`,
  deduplicated, then sorted with JS default `sort()` = **UTF-16 code-unit order** (not locale,
  not code-point order: `"10" < "9" < "a" < "zebra" < "~ tilde" < "\u0085next" < "ä" < "éclair"
  < "😀 smile"`; in Kotlin `sorted()` on `String` does the same).
- **media** (`mediaId` keyed): with `A` = ancestor ids, first walk `local.media` in order: skip
  an item if its id is in `A` but not on the server (server removal wins over local edits);
  otherwise output the item itself if the server lacks it, else the server descriptor with the
  caption merged (below). Then append `server.media` items, in server order, whose id is neither
  in `local` nor in `A` (server additions; a server item in `A` but missing locally was removed
  locally and stays removed). Server reordering is ignored; local order wins. A descriptor
  present on both sides is the **server** version, even if only local changed other fields.
- **caption** (inside the server descriptor): `localChanged = local.caption !== ancestor?.caption`
  (ancestor descriptor of the same id, absent = `undefined`). If not `localChanged`, or
  `local.caption === server.caption` → the server descriptor unchanged. Otherwise local wins
  regardless of edit times: if `local.caption` is absent → server descriptor without `caption`;
  else server descriptor with `caption = local.caption`, and the `caption` key moves to the
  **end** of the descriptor object (`{...rest, caption}`), which matters only for byte-exact
  JSON re-serialization.
- `schemaVersion` = 1, `createdAtUtc` = ancestor's, `editedAtUtc` = `nowUtc` verbatim (no
  reformatting: `2026-04-01T02:00:00+02:00` stays as is).

`normalizeTag(s)` (from `domain/tags.ts`): `s.normalize("NFKC")`, then `trim()`, then replace
every run of `/\s+/u` by one space, then `toLowerCase()`; `null` if the result is empty or longer
than 64 **UTF-16 code units** (32 emoji = 64 units are kept, 33 are dropped). Kotlin pitfalls
exercised by `edge:` cases:
- JS `\s` and `trim()` use the ECMAScript whitespace set: U+0009–U+000D, U+0020, U+00A0, U+1680,
  U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, U+3000, **U+FEFF**. Not included: U+0085 (NEL),
  U+200B (zero width space), both kept in the output. Kotlin's `trim()`/`Regex("\\s")` differ
  (no U+FEFF, Java `\s` is ASCII-only without `UNICODE_CHARACTER_CLASS`), so use an explicit set.
- `toLowerCase()` is locale-independent full Unicode lowercasing (Kotlin `lowercase()` /
  `Locale.ROOT`): `İ` → `i` + U+0307, word-final `Σ` → `ς` (`ΟΔΟΣ` → `οδος` with U+03C2), `ß`
  unchanged (`STRASSE` → `strasse` is a different tag from `straße`).
- NFKC: `ﬁ` → `fi`, fullwidth letters → ASCII, U+00A0/U+3000 → space.

**`metadataEquivalent(left, right)`**, `args = { left, right }`, `result` = boolean. True iff
`schemaVersion`, `takenAtUtc`, `utcOffsetMinutes`, `timeZone`, `note`, `createdAtUtc` are equal
(strings compared exactly, no date parsing), the normalized tag lists are equal **as ordered
lists** (normalization drops empty tags but does not sort or deduplicate: `["b","a"]` ≠
`["a","b"]`, `["a","b","B"]` ≠ `["a","b"]`, `["a"," ","b"]` = `["a","b"]`), `location` and
`media` are deep-equal (as described for location; arrays by length and position; object key
order irrelevant; an absent `caption` differs from a present one), and `favorite === true` is
the same on both sides. `editedAtUtc` is ignored.

### Export area: common notes

All four export files use the function-case shape `{ name, fn, args, result }`. `result` is the
return value passed through the generator's `toJson`, or `{ "error": "<ClassName>", "message": "..." }`
when the call throws. Encodings used here:

- Bytes are lowercase hex strings.
- A `Date` is encoded as `toISOString()` (`YYYY-MM-DDTHH:mm:ss.sssZ`, extended years as `±YYYYYY-...`).
  An invalid `Date` is encoded as `"Invalid Date"`.
- `NaN` is encoded as `{ "$number": "NaN" }`. `-0` is encoded as `0`.
- Optional object properties that are `undefined` are left out (for example `caption`). They are never `null`.
- `constants` cases (`fn: "constants"`, `args: {}`) list exported constants by name so Kotlin can check its
  own `*Constants` against them.

Error classes in this area: `InvalidEntryNoteError` (parse), `MalformedObjectError`,
`DecryptionError`, `UnsupportedFormatError` (crypto), `RangeError` (JS `Date.prototype.toISOString` on
an invalid time value, message `Invalid time value`). The Kotlin port should map these to its own exception
types and compare the class name and message through a fixed table.

### `export/layout.json`

`planEntryExport`, `frontMatter`, `yamlQuote`, `EXPORT_TEXT` and the layout constants. Every scenario of
`packages/core/test/export/export-layout.test.ts` is here, except `planParts`, `partFileName` and
`clampZipDate`, which are in `export/zip.json`. Case names: `<fn>: <TS test name>` and `<fn>: edge: ...`.

| `fn` | Call | `args` |
|---|---|---|
| `planEntryExport` | `planEntryExport(entryId, metadata)` | `{ entryId, metadata }` |
| `frontMatter` | `frontMatter(entryId, metadata)` | `{ entryId, metadata }` |
| `yamlQuote` | `yamlQuote(value)` | `{ value }` |
| `EXPORT_TEXT.readme` | `EXPORT_TEXT.readme(date)` | `{ date }` |
| `EXPORT_TEXT.missingHeader` | the constant string | `{}` |
| `EXPORT_TEXT.missingLine` | `EXPORT_TEXT.missingLine(folder, file, reason)` | `{ folder, file, reason }` (result is `folder + file + "\t" + reason`) |
| `constants` | `EXPORT_NOTE_FILE`, `EXPORT_README_FILE`, `EXPORT_MISSING_FILE`, `EXPORT_FALLBACK_EXTENSION`, `EXPORT_EXTENSIONS` (MIME type to extension, as an object in declaration order) | `{}` |

`metadata` is an `EntryMetadataV1` object in JSON form. The TS test's `metadata()` helper leaves out
`location.accuracyMeters`. The goldens add `accuracyMeters: null` (or a number) so that every input is
valid against the schema. `frontMatter` never reads `accuracyMeters`, so the output does not change.
Two cases have metadata that the contract schema rejects. They carry an extra field
`"metadataValid": false`: `takenAtUtc` with an explicit `+02:00` offset (TS `Date.parse` accepts it) and
`takenAtUtc: "not a date"`, which throws `RangeError: Invalid time value`. A Kotlin model that refuses such
metadata may skip these two cases, but only these two.

`planEntryExport` result: `{ folder, noteFile, noteText, media: [{ mediaId, fileName, plaintextBytes }], mtime }`.
`mtime` is `new Date(takenAtUtc)`, an ISO string of the UTC instant (not the local time).

Algorithm (byte-exact):

1. `local = new Date(Date.parse(takenAtUtc) + utcOffsetMinutes * 60000).toISOString()`. The shift moves
   the UTC instant and then formats it as UTC, which gives "local wall time" digits. Years 0000-9999 print
   as 4 digits. Outside that range JS prints `±YYYYYY`. One edge case shows the odd result: a local time in year -1 gives
   `folder "/000001/-000001-12_31T23_AbCdEfGh/"` and `date: -000001-12T31T23:30-01:00`, because the code
   below slices fixed positions.
2. `offsetText(m)` = (`"-"` if `m < 0`, else `"+"`) + `pad2(floor(|m| / 60))` + `":"` + `pad2(|m| % 60)`.
   Zero gives `+00:00`.
3. `folder`: `[year, month, day] = local[0..10).split("-")` (missing parts are `""`),
   `time = local[11..16)` with only the first `":"` removed (`HHmm`), and
   `folder = "${year}/${month}/${year}-${month}-${day}_${time}_${entryId[0..8)}/"`. The id prefix counts UTF-16
   code units, and an id shorter than 8 characters is used whole. `noteFile = folder + "entry.md"`.
4. Media file name at position `p` (0-based) = `String(p + 1).padStart(3, "0") + "." + ext`, where `ext` is
   `EXPORT_EXTENSIONS[mimeType]` looked up by exact, case-sensitive string (`IMAGE/JPEG` and `""` give
   `bin`), or `bin` if the type is not in the table. Position 1000 would give `1001`, with no truncation.
5. `noteText = frontMatter(entryId, metadata) + "\n" + note + "\n"`. The note is written raw: no
   normalization, CRLF stays CRLF, and an existing trailing newline is kept, so the text ends with two newlines.

`frontMatter` exact bytes. The lines below are joined with `"\n"`, and the last line is followed by a
`"\n"` (the TS code joins a list whose last element is `""`):

```
---
id: <entryId>                                   (raw, never quoted)
date: <local[0..10)>T<local[11..19)><offsetText>  (seconds kept, fraction dropped)
tags: [<yamlQuote(tag) joined by ", ">]           ("tags: []" when empty)
favorite: true                                  (only when metadata.favorite === true)
location: { latitude: <lat.toFixed(5)>, longitude: <lon.toFixed(5)>, source: <source> }   (only when location != null; source raw)
media: [<item>, <item>, ...]                     (items joined by ", ")
---
```

Each `<item>` is `{ ` + parts joined by `", "` + ` }`, where the parts in order are `file: <fileName>`,
then `original_name: <yamlQuote(originalFileName)>` (only when it is not null), then `kind: <kind>`, then
`caption: <yamlQuote(caption)>` (only when present).

`Number.prototype.toFixed(5)` must be copied exactly. Take the exact decimal value of the binary double,
round half up on the magnitude, and print plain decimal (|value| <= 180, so no exponent form). The
sign is printed when `x < 0`, even if the rounded digits are all zero: `-0.000001` gives `-0.00000`.
`0`, and the `-0` that JSON cannot carry, give `0.00000`. Examples in the goldens: `1.000005` gives
`1.00001`, because its double is slightly above the half. `0.0000025` gives `0.00000`, because its double is
slightly below the half. `179.999995` gives `180.00000`. In Kotlin, use
`BigDecimal(x).abs().setScale(5, RoundingMode.HALF_UP).toPlainString()` with a `"-"` prefix when `x < 0`.
Use the `BigDecimal(Double)` constructor, not `BigDecimal.valueOf`, which rounds through the shortest string.

`yamlQuote(value)`:

- The value is returned unchanged if it is not empty and does not match `/[:#[\]{},"']|^\s|\s$/`. That
  means it contains none of `: # [ ] { } , " '`, does not start with whitespace and does not end with whitespace. `^` and `$`
  mean the start and end of the whole string (no multiline flag).
- Otherwise the result is `"` + value with every `\` replaced by `\\`, then every `"` replaced by `\"`, + `"`.
  Nothing else is escaped: newlines, tabs, control characters and non-ASCII characters are written raw. An inner newline
  does not trigger quoting at all (`a\nb` is returned as is), so front matter can contain a raw line
  break inside a tag.
- JS `\s` is exactly U+0009-U+000D, U+0020, U+00A0, U+1680, U+2000-U+200A, U+2028, U+2029, U+202F, U+205F,
  U+3000 and U+FEFF. It does NOT include U+0085, U+180E or U+200B. Kotlin's `\s` and `Char.isWhitespace()` both
  differ from this set, so use an explicit set. Edge cases cover NBSP, BOM, U+2028, U+0085 and U+200B.
- YAML keywords and numbers (`true`, `123`), a leading `-`, a full-width colon `：` and `%&*|>!@\``
  are not quoted.

### `export/parse-entry.json`

`parseEntryNote(source)` (args `{ source }`), plus the `planEntryExport` calls that make the inputs.
This covers every scenario of `packages/core/test/export/parse-entry.test.ts`. Each round-trip TS test
appears as two cases with the same suffix:
`planEntryExport: <name>` (args `{ entryId, metadata }`, where metadata went through `EntryMetadataV1.parse`) and
`parseEntryNote: <name>`, whose `source` is exactly the `noteText` of the plan case. The TS test
"writes captions and reads them back with audio items" checks that `noteText` contains
`caption: "Sunset, \"late\""`. That text is inside the plan case's `noteText`.

Result: `{ id, takenAtUtc, utcOffsetMinutes, tags, favorite, location, media, note }` in this key order.
`location` is `null` or `{ latitude, longitude, source }`. `media` items are `{ file, originalName, kind, caption? }`, with
`originalName` `null` when absent and `caption` left out when absent. Every rejection throws
`InvalidEntryNoteError` with message `This file is not an entry from a Kodakiko export.`, except invalid
calendar values in the date (`RangeError`, `Invalid time value`).

Algorithm (byte-exact, in this order):

1. `source` must start with `"---\n"`. CRLF files and a leading BOM are rejected.
2. `end = source.indexOf("\n---\n", 3)`. The search starts at index 3, so in `"---\n---\n..."` the opening
   fence's own newline matches (`end = 3`). If there is no match, the note is rejected. A closing `---` at the very end without `\n` is rejected.
3. `header = source.slice(4, end)`. When `end = 3`, JS `slice(4, 3)` is `""`. Kotlin's `substring` would throw,
   so clamp it. Split the header on `"\n"`. For each line, `colon = line.indexOf(":")`. If `colon > 0`, then
   `key = line[0..colon).trim()` and `value = line[colon+1..).trim()`, stored in a map where the last duplicate wins.
   Lines without a colon, or with a colon at index 0, are skipped. JS `trim()` strips the same whitespace set as `\s` above.
4. `date` must match `/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})([+-])(\d{2}):(\d{2})$/`, with ASCII digits only and no
   milliseconds, no `Z` and no missing offset. A missing date (`""`) is rejected. JS `$` without the `m` flag matches only at the end
   of the input. Kotlin/Java `$` also matches before a final line terminator, so use `\z` or `matchEntire`.
   `utcOffsetMinutes = sign * (hh * 60 + mm)`, with no range check: `+01:99` gives 159, and `-00:00` gives -0, encoded as 0.
5. `takenAtUtc = new Date(Date.parse(date[1] + "Z") - utcOffsetMinutes * 60000).toISOString()`. V8's
   `Date.parse` accepts month 01-12, day 01-31 for every month, overflowing into the next month
   (`2026-02-30` is read as `2026-03-02`), hour 00-24 where 24 is only allowed as `24:00:00` (the next midnight),
   minute 00-59 and second 00-59. Anything else gives NaN, and `toISOString` then throws `RangeError: Invalid time value`.
   The date is checked before anything below, so it decides the error when several fields are bad.
6. `tags = flow(map.tags ?? "[]")`, then `media = flow(map.media ?? "[]")`, then
   `location = "location" in map ? flow(map.location) : null`, parsed in this order. After that, tags and media must
   be lists. A scalar such as `tags: beach` or an empty `tags:` is rejected.
7. `body = source.slice(end + 5)`. `note` = (`body` without its first character if `body` starts with `"\n"`, else `body`)
   with exactly one trailing `"\n"` removed if present (JS `/\n$/`). Use `removeSuffix("\n")` in Kotlin, because
   `Regex("\n$")` also matches before a final newline.
8. Fields are checked in this order: `id` (it must be present as a string, an empty value `id:` gives `""`), each
   tag, `favorite = (map.favorite === "true")`, `location`, then the media items.
   - `location` is `null` if the field is absent or parses to a string or list (an empty `location:` is `null`). For a map:
     `latitude = Number(text(map.latitude))`, then `longitude`, then `source = text(map.source)`. A missing key is rejected.
     JS `Number(string)` trims JS whitespace, maps `""` to `0`, accepts `0x10` (16), `0b..`, `0o..`, `1e2`,
     `.5`, `5.`, `+1` and `Infinity`, and gives `NaN` (encoded `{ "$number": "NaN" }`) for anything else.
   - Each media item must be a map (not a string or list). `kind = text(item.kind)` must be `photo`, `video` or
     `audio`. `caption` is left out when the key is absent and must be a string otherwise (`caption: ""`
     is kept as `""`). `file` is required. `original_name` maps to `originalName`, which is `null` when absent. Unknown keys are ignored.
   - `text(v)` rejects anything that is not a string (missing key, list or map).

Flow parser `flow(text)` (position-based, used for `tags`, `media` and `location`):

- `skip` advances over U+0020 only. Tabs are not skipped.
- `value`: skip, then `[` starts a list, `{` starts a map, anything else is a scalar. Text after the top-level value
  is ignored (`[ , x] junk` gives `["", "x"]`).
- List: after `[`, skip, and an immediate `]` gives `[]`. Otherwise repeat: `value`, skip, read one character. `]` ends
  the list, `,` continues, and anything else, including the end of the text, is rejected (`[a; b]` gives the single item
  `"a; b"`, because `;` is not a delimiter).
- Map: after `{`, repeat: skip, find the next `:` anywhere after the position with `indexOf` (if there is none, reject),
  `key = text[pos..colon).trim()`, `pos = colon + 1`, `value`, skip, read one character. `}` ends the map, `,` continues,
  and anything else is rejected. `{}` is rejected because it has no colon.
- Scalar: skip, and if the next character is `"`, read a quoted scalar. Otherwise read up to the first `,`, `]` or `}` (not `[` or `{`)
  or the end of the text, and `trim()` it (JS set, so a leading tab is trimmed away). A quoted scalar reads until the next unescaped
  `"`. A `\` is dropped and the next character is taken literally, so `\n` gives `n` and `\\` gives `\`. Nothing is trimmed. At the end
  of the text without a closing quote, the parser returns what it has read and moves past the end. The caller then sees no
  delimiter and rejects the note.

### `export/kdkx.json` + `export/kdkx/<name>.kdkx`

Encrypted export parts. This covers every scenario of `packages/core/test/export/kdkx.test.ts`. Functions:
`createKdkxWriter`, `deriveKdkxKey`, `readKdkxParams`, `isKdkx`, `decryptKdkx`, plus `constants`
(`KDKX_EXTENSION ".kdkx"`, `KDKX_CHUNK_BYTES 1048576`, `KDKX_SALT_BYTES 16`).

File layout (all integers big-endian, `DataView` default):

| Offset | Size | Content |
|---|---|---|
| 0 | 4 | magic `"KDKX"` = `4b 44 4b 58` |
| 4 | 1 | version `0x01` |
| 5 | 4 | `opsLimit`, u32 big-endian |
| 9 | 4 | `memLimitBytes`, u32 big-endian |
| 13 | 16 | Argon2id salt |
| 29 | 24 | STREAM header: `"KDKS"` `0x01` `0x14` `0x00 0x00` + 16-byte nonce prefix (see "STREAM objects") |
| 53 | ... | chunk ciphertexts |

- `key = crypto_pwhash(32, UTF-8(passphrase), salt, opsLimit, memLimitBytes, ARGON2ID13)`. The passphrase
  is UTF-8 encoded as is, with NO NFKC (unlike account passwords). A `deriveKdkxKey` edge case uses a
  decomposed accent and a ligature to show this.
- Chunks are the STREAM chunks of the "STREAM objects" section: 2^20 plaintext bytes per non-final chunk, ciphertext =
  plaintext + 16-byte tag, nonce = prefix ‖ chunk index (7-byte big-endian) ‖ (`0x01` final / `0x00` not final),
  AD = UTF-8 of the lowercase hex of the 24-byte STREAM header only. The 29-byte KDKX prefix is NOT
  authenticated (see the `decryptKdkx` edge case "changed opsLimit, original key", which still decrypts).
- There is always exactly one final chunk, and it is the last one. An empty payload gives one 16-byte final chunk (file size 69).
  File size = 53 + plaintext + 16 × max(1, ceil(n / 2^20)). A payload of exactly 2^20 bytes gives ONE
  final full chunk.

`createKdkxWriter` cases. `fn: "createKdkxWriter"` with args
`{ passphrase, kdf: { opsLimit: 2, memLimitBytes: 8388608 }, randomSeed, plaintextSeed, plaintextSize, operations }`.
Steps, as in the TS test's `encrypt` helper:

1. `sodium` = the golden Sodium (counter random from `randomSeed`).
2. `plaintext = counterBytes(plaintextSeed, plaintextSize)`.
3. `salt = sodium.randomBytes(16)`, the first draw, so it equals `counterBytes(randomSeed, 16)`.
4. `key = deriveKdkxKey(sodium, passphrase, { salt, ...kdf })`.
5. `writer = createKdkxWriter(sodium, key, { salt, ...kdf })`. This draws the 16-byte nonce prefix,
   `counterBytes(randomSeed + 16, 16)`. `result.randomDraws` is `[16, 16]`.
6. Run `operations` in order. `{ op: "push", size }` calls `writer.push(next size bytes of plaintext)`.
   The plaintext cursor advances even if the call throws. `{ op: "finish" }` calls `writer.finish()`.
7. The file is `writer.header` (53 bytes) followed by every returned chunk in order.

`result`: `{ randomDraws, salt, key, header, operations, file }`. `operations[i]` is `{ chunkSizes: [...] }` for
a push (the sizes of the chunks it returned, often `[]`), `{ chunkSize }` for a finish, or an error object.
`file` is `{ file, size, sha256 }` when the file is stored under `export/kdkx/`, or `{ size, sha256 }` when it is
not stored to save space (the exact-chunk, two-chunks-plus-7 and chunk+1 cases, 1 to 2 MiB each). For those, rebuild the file and
compare its SHA-256.

Writer behavior to mimic:

- `push` appends to a pending buffer and emits non-final chunks only `while pending.length > 2^20`
  (strictly greater). A full chunk is held back until more bytes arrive or `finish` is called. So pushing exactly
  2^20 bytes returns `[]`, and `finish` emits a full final chunk. The edge case "held back until finish" uses the
  same seeds as the exact-chunk case and produces the same file (same sha256) with different push results. The case
  "emitted only once more than one chunk is pending" pushes `2^20 - 1, 1, 1`, and only the third push returns a
  chunk. Empty pushes are allowed and return `[]`.
- `finish` encrypts the remaining bytes (0 to 2^20) as the final chunk.
- After `finish`, both `push` and `finish` throw `MalformedObjectError` with message
  `Not a Kodakiko encrypted export, or the file is damaged.` and change nothing.

Stored files: `empty.kdkx` (69 bytes), `five-bytes.kdkx`, `non-ascii-passphrase.kdkx`, `after-finish.kdkx`
(74 bytes each), and `chunk-plus-10.kdkx` (1,048,671 bytes, the `(the source file)` case of the rejection test: one full
non-final chunk at index 0, plus a 10-byte final chunk at index 1).

`deriveKdkxKey`: args `{ passphrase, params: { salt, opsLimit, memLimitBytes } }`, result = the key (hex).

`readKdkxParams`: args `{ file }` (a stored file reference) or `{ bytes }` (hex). Result
`{ opsLimit, memLimitBytes, salt }`. It throws `MalformedObjectError` (`Not a Kodakiko encrypted export, or the file
is damaged.`) when the magic is wrong, when the file is shorter than 69 bytes (29 + 24 + 16), or when the version is not 1. The u32 values are
read unsigned and not range-checked (`0xffffffff` gives `4294967295`). The STREAM header is not looked at here.

`isKdkx`: args `{ bytes }` or `{ file }`. The result is `true` when the bytes are at least 4 long and start with `KDKX` (case-sensitive).
`"KDKX"` alone is `true`.

`decryptKdkx`: args `{ file, mutations, passphrase, key }`. `key` is
`deriveKdkxKey(passphrase, readKdkxParams(original file))`, given in hex for convenience. To run a case, load `file`, apply
`mutations` in order, and call `decryptKdkx(sodium, key, mutated)`. The result is `{ size, sha256 }` of the plaintext,
or an error object. Mutations: `{ op: "xor", offset, value }` (byte at `offset` ^= `value`),
`{ op: "truncate", length }` (keep the first `length` bytes), `{ op: "set", offset, bytes }` (overwrite with hex
bytes) and `{ op: "append", bytes }`. The TS rejection test ("wrong passphrase, flipped byte, truncated") is replayed on
`chunk-plus-10.kdkx` (the same chunk+10 payload as the TS test, written in one push) with the TS mutations (flip at
`length - 20`, truncate by 2^19). The multi-chunk edge cases (truncated at the chunk boundary `53 + 2^20 + 16`, a final
piece shorter than the tag, a flipped byte in the first chunk) use the same file.

Decryption algorithm (byte-exact error behavior):

1. `readKdkxParams(file)` (same errors as above).
2. `streamHeader = file[29..53)`, `offset = 53`, `index = 0`.
3. While `file.length - offset > 2^20 + 16`, decrypt `file[offset, offset + 2^20 + 16)` as a non-final chunk `index`.
   Then `offset += 2^20 + 16` and `index++`. The split uses the fixed 2^20 size, whatever the header's exponent byte says.
4. If `file.length - offset < 16`, throw `MalformedObjectError` (the KDKX message).
5. Decrypt the rest as the final chunk `index`.
6. Each chunk decryption first validates the STREAM header (bad magic, non-zero reserved bytes or an exponent outside 10-24 →
   `MalformedObjectError` with message `The stored file is not in the expected format.`, and a STREAM version other than 1 →
   `UnsupportedFormatError`). Next, a ciphertext longer than the header's chunk size + 16 →
   `MalformedObjectError` (same message). Last, an AEAD failure → `DecryptionError` `The data could not be decrypted.`
   This covers a wrong key, a flipped byte, a changed nonce prefix, an appended byte, a file truncated in the middle of the final chunk,
   and a file truncated exactly at a chunk boundary, where the last full chunk is not final.

### `export/zip.json`

Deviation: `packages/core` has no ZIP writer. The client uses `fflate` in `apps/client`, which is not a
dependency of `@kodakiko/core`. This file covers the core's ZIP part layer only: `planParts`, `partFileName`,
`clampZipDate` and the part constants. These are the export-layout test cases for those functions, plus edge cases.
The ZIP container bytes have no golden.

| `fn` | Call | `args` |
|---|---|---|
| `planParts` | `planParts(files, partMaxBytes, partMaxFiles)` | `{ files: [{ entryId, path, bytes }], partMaxBytes, partMaxFiles }` |
| `partFileName` | `partFileName(exportDate, partNumber)` | `{ exportDate, partNumber }` |
| `clampZipDate` | `clampZipDate(new Date(date))` | `{ date }`, a string passed to `new Date(string)` |
| `constants` | `EXPORT_FILE_OVERHEAD_BYTES 1024`, `EXPORT_PART_MAX_FILES 65000`, `EXPORT_PART_MAX_BYTES 4026531840`, `EXPORT_WEB_MEMORY_PART_MAX_BYTES 1073741824`, `EXPORT_MTIME_MIN`, `EXPORT_MTIME_MAX` (ISO) | `{}` |

`planParts` (result: a list of parts, each a list of the input file objects, in input order, never sorted). The algorithm is greedy:
for each file, `fits = bytes + file.bytes + 1024 <= partMaxBytes && current.length < partMaxFiles`. If it does not
fit and the current part is not empty, close the current part and start a new one with `bytes = 0`. Then add the file and
`bytes += file.bytes + 1024`. Consequences: a file is never split; a file larger than the limit gets a part of its own; an
entry's files can span parts; `partMaxFiles` 0 or `partMaxBytes` 0 still put one file in each part; zero-byte
files still count 1024 bytes. An empty input gives `[]`. `Number.MAX_SAFE_INTEGER` (9007199254740991) appears as
a limit. All sums stay below 2^53.

`partFileName` = `"kodakiko-export-" + exportDate + "-part" + String(partNumber).padStart(2, "0") + ".zip"`.
`exportDate` is used as given. 100 gives `part100`, 0 gives `part00`, and -1 gives `part-1` (the sign counts toward the width).

`clampZipDate`: the minimum is `1980-01-01T12:00:00.000Z` and the maximum is `2099-12-31T12:00:00.000Z`. If
`t < min` the result is min, if `t > max` the result is max, and otherwise the input is returned unchanged (both bounds are inclusive, at millisecond
precision). Midnight 1980-01-01, and the DOS limit 2107-12-31, are clamped. An invalid date (`"not a date"`) compares
false both ways and is returned unchanged, encoded as `"Invalid Date"`. Inputs include ISO strings without
milliseconds (`1970-01-01T00:00:00Z`), the extended years `-000001-01-01T00:00:00.000Z` and
`+275760-09-13T00:00:00.000Z` (the largest JS date), and the result is always printed with `toISOString()`.
