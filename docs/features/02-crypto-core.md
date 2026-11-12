# 02 — Crypto core (`packages/core`) + test vectors

Status: draft 2 (2026-11-11, after review 1).

Goal: the complete pure crypto layer from `docs/design/crypto.md`: the `Sodium` port, constants,
errors, padding, sealed blobs, the STREAM object format with stateful encryptor/decryptor,
recovery words, share payloads, a Node test implementation of the port, committed test vectors and
the tests that replay them. No I/O, no platform imports.

Read first: `docs/design/crypto.md` (normative for every value and format below);
`docs/engineering-guidelines.md` §§4-5, 12; `docs/facts.md` F-CRY-01..03, F-LIB-01..04,
F-TOOL-13. Depends on spec 01.

Rules for this package (they apply to every later `core` file too):

- Relative imports end in `.ts`.
- Allowed imports: relative files, `@kodakiko/contract`, `zod`. Nothing else in `src/`.
- Every number with meaning (sizes, bit widths, masks, version bytes) comes from `constants.ts`
  (the lint rule from spec 01 enforces it).
- Exported names exactly as written here; no additional exports without asking.

## 1. Files

| Path | Section |
|---|---|
| `packages/core/src/platform-globals.d.ts` | 2 |
| `packages/core/src/crypto/text.ts` | 3 |
| `packages/core/src/crypto/errors.ts` | 3 |
| `packages/core/src/crypto/constants.ts` | 4 |
| `packages/core/src/crypto/sodium.ts` | 5 |
| `packages/core/src/crypto/bytes.ts` | 6 |
| `packages/core/src/crypto/password.ts` | 6 |
| `packages/core/src/crypto/padding.ts` | 7 |
| `packages/core/src/crypto/sealed-blob.ts` | 7 |
| `packages/core/src/crypto/stream.ts` | 8 |
| `packages/core/src/crypto/wordlist-en.ts` | 9 |
| `packages/core/src/crypto/recovery-words.ts` | 9 |
| `packages/core/src/crypto/share-payload.ts` | 10 |
| `packages/core/src/crypto/index.ts` | barrel of every module above except `platform-globals.d.ts` |
| `packages/core/src/index.ts` | `export * from './crypto/index.ts';` |
| `packages/core/src/crypto/deterministic-bytes.ts` | 11 |
| `packages/core/src/crypto/vector-check.ts` | 12 |
| `packages/core/src/crypto/vector-types.ts` | 12 |
| `packages/core/test/sodium-node.ts` | 11 |
| `packages/core/scripts/generate-vectors.ts` | 12 |
| `packages/core/vectors/*.json` (7 files) + `packages/core/vectors/index.ts` | 12 |
| `packages/core/test/vectors.test.ts` | 13 |
| `packages/core/test/crypto.test.ts` | 13 |

## 2. `platform-globals.d.ts`

`core` compiles with `lib: ["ES2023"]` only (no DOM, no Node types), but needs the UTF-8 codec
that Node, Hermes and browsers all provide. Declare exactly:

```ts
declare class TextEncoder { encode(input?: string): Uint8Array; }
declare class TextDecoder { constructor(label?: string, options?: { fatal?: boolean }); decode(input?: Uint8Array): string; }
```

No other ambient declarations.

## 3. `text.ts` and `errors.ts`

`text.ts`: `export const CRYPTO_TEXT = Object.freeze({ ... } as const)` with one message per key:
`decryptionFailed`, `unsupportedFormat`, `malformedPayload`, `malformedObject`, `truncatedObject`,
`invalidChunk`, `invalidKeyLength`, `invalidRecoveryWordCount`, `unknownRecoveryWord` (a function
`(position: number) => string`, 1-based position in the message), `recoveryChecksumMismatch`,
`shareCollectionMismatch`. Wording: plain sentences, American English, no trailing period
inconsistency (all end with a period).

`errors.ts` (erasable syntax only: no parameter properties):

```ts
export class DecryptionError extends Error {}
export class UnsupportedFormatError extends Error {}
export class MalformedPayloadError extends Error {}
export class MalformedObjectError extends Error {}
export class TruncatedObjectError extends Error {}
export class InvalidChunkError extends Error {}
export class InvalidKeyLengthError extends Error {}
export class InvalidRecoveryWordsError extends Error {
  readonly wordPosition: number | null;
  constructor(wordPosition: number | null, message: string) {
    super(message);
    this.wordPosition = wordPosition;
  }
}
```

Classes do not set `name` or any other field beyond the one shown; tests check `instanceof`.

## 4. `constants.ts`

Values from `crypto.md` §3 plus the bit-level constants the code needs:

```ts
export const KEY_BYTES = 32;
export const AEAD_NONCE_BYTES = 24;
export const AEAD_TAG_BYTES = 16;
export const SALT_BYTES = 16;
export const FORMAT_VERSION_SEALED_BLOB = 0x01;
export const FORMAT_VERSION_SHARE_PAYLOAD = 0x01;
export const STREAM_MAGIC: Readonly<Uint8Array> = Uint8Array.of(0x4b, 0x44, 0x4b, 0x53);
export const STREAM_VERSION = 0x01;
export const STREAM_CHUNK_SIZE_EXPONENT = 20;
export const STREAM_CHUNK_SIZE_EXPONENT_MIN = 10;
export const STREAM_CHUNK_SIZE_EXPONENT_MAX = 24;
export const STREAM_NONCE_PREFIX_BYTES = 16;
export const STREAM_COUNTER_BYTES = 7;
export const STREAM_HEADER_BYTES = 24;
export const STREAM_HEADER_MAGIC_OFFSET = 0;
export const STREAM_HEADER_VERSION_OFFSET = 4;
export const STREAM_HEADER_EXPONENT_OFFSET = 5;
export const STREAM_HEADER_RESERVED_OFFSET = 6;
export const STREAM_HEADER_RESERVED_BYTES = 2;
export const STREAM_HEADER_PREFIX_OFFSET = 8;
export const STREAM_FINAL_FLAG = 0x01;
export const STREAM_NOT_FINAL_FLAG = 0x00;
export const STREAM_MAX_CHUNK_INDEX = Number.MAX_SAFE_INTEGER;
export const STREAM_MIN_OBJECT_BYTES = 40;
export const PADDING_BLOCK_BYTES = 256;
export const PADDING_MARKER = 0x80;
export const BYTE_BITS = 8;
export const BYTE_VALUES = 256;
export const ARGON2ID_MIN_MEM_BYTES = 134_217_728;
export const ARGON2ID_MIN_WORK = 4_294_967_296;
export const ARGON2ID_START_MEM_BYTES = 268_435_456;
export const ARGON2ID_START_OPS = 16;
export const RECOVERY_WORD_COUNT = 24;
export const RECOVERY_WORD_BITS = 11;
export const RECOVERY_CHECKSUM_BITS = 8;
export const RECOVERY_WORDLIST_SIZE = 2048;
export const GENERIC_HASH_BYTES = 32;
export const FINGERPRINT_BYTES = 8;
export const AD_SEPARATOR = 0x00;
export const STREAM_CHUNK_SIZE_BASE = 2;
export const BYTE_MASK = 0xff;
export const HEX_RADIX = 16;
export const HEX_CHARS_PER_BYTE = 2;
export const FINGERPRINT_GROUPS = 4;
export const FINGERPRINT_GROUP_CHARS = 4;
export const VECTOR_FORMAT = 1;
```

Plus the two frozen tables (exact strings from `crypto.md` §3; `AD_CONTEXTS` has **13** keys
including `linkSecretByMaster`):

```ts
export const KDF_CONTEXTS = Object.freeze({ login: 'kdklogin', recovery: 'kdkrecov', media: 'kdkmedia', link: 'kdklink_' } as const);
export type KdfContext = (typeof KDF_CONTEXTS)[keyof typeof KDF_CONTEXTS];
export const KDF_SUBKEY_IDS = Object.freeze({ login: 1, recoveryLogin: 1, mediaOriginal: 1, mediaThumbnail: 2, linkCollectionKey: 1, linkAccessToken: 2 } as const);
export const AD_CONTEXTS = Object.freeze({ masterKeyByKek: 'kodakiko/v1/wrap/master-key-by-kek', /* …13 entries… */ } as const);
export type AdContext = (typeof AD_CONTEXTS)[keyof typeof AD_CONTEXTS];
```

## 5. `sodium.ts`

Types only, verbatim from `crypto.md` §2: `Sodium`, `Argon2idInput`, `BoxKeypair`. `KdfContext`
is imported from `constants.ts` (not redeclared).

## 6. `bytes.ts` and `password.ts`

`bytes.ts` (byte helpers used across core):

```ts
export function concatBytes(...parts: readonly Uint8Array[]): Uint8Array;
export function utf8Encode(text: string): Uint8Array;
export function utf8Decode(bytes: Uint8Array): string;
export function bytesEqual(left: Uint8Array, right: Uint8Array): boolean;
export function writeUint56BigEndian(target: Uint8Array, offset: number, value: number): void;
export function wipe(bytes: Uint8Array): void;
export function bytesToHex(bytes: Uint8Array): string;
export function hexToBytes(hex: string): Uint8Array;
```

- `utf8Decode` uses `new TextDecoder('utf-8', { fatal: true })`; invalid UTF-8 throws
  `MalformedPayloadError`.
- `bytesEqual` is length check + full loop (no early exit) — it compares non-secret values in core,
  but constant time costs nothing here.
- `writeUint56BigEndian`: `value` must be an integer in `[0, STREAM_MAX_CHUNK_INDEX]` else
  `InvalidChunkError`; writes 7 bytes using division by `BYTE_VALUES` (not bit shifts: JavaScript
  shifts are 32-bit).
- `wipe`: `bytes.fill(0)` (crypto.md §1 rule 4).
- `bytesToHex`: lowercase, `HEX_CHARS_PER_BYTE` characters per byte. `hexToBytes`: accepts
  lowercase or uppercase; odd length or a non-hex character → `MalformedPayloadError`.

`password.ts`: `export function passwordToBytes(password: string): Uint8Array` =
`utf8Encode(password.normalize('NFKC'))`. The only place a password becomes bytes.

## 7. `padding.ts` and `sealed-blob.ts`

`padding.ts` (crypto.md §6.2):

```ts
export function padIso7816(bytes: Uint8Array, blockBytes: number): Uint8Array;
export function unpadIso7816(bytes: Uint8Array, blockBytes: number): Uint8Array;
```

- Pad: append `PADDING_MARKER`, then zeros until `length % blockBytes === 0`. Always adds at
  least one byte (a 256-byte input becomes 512).
- Unpad: input length must be a positive multiple of `blockBytes`; scan back over zeros; the next
  byte must be `PADDING_MARKER`; else `MalformedPayloadError`.

`sealed-blob.ts`, signatures from `crypto.md` §6.1 plus JSON helpers:

```ts
export function buildAdditionalData(context: AdContext, bindingId: string): Uint8Array;
export function sealBlob(sodium: Sodium, key: Uint8Array, plaintext: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export function openBlob(sodium: Sodium, key: Uint8Array, blob: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export function wrapKey(sodium: Sodium, wrappingKey: Uint8Array, keyToWrap: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export function unwrapKey(sodium: Sodium, wrappingKey: Uint8Array, wrapped: Uint8Array, context: AdContext, bindingId: string): Uint8Array;
export type PayloadMigrations = ReadonlyMap<number, (raw: Record<string, unknown>) => Record<string, unknown>>;
export function sealJson(sodium: Sodium, key: Uint8Array, value: { readonly schemaVersion: number }, context: AdContext, bindingId: string): Uint8Array;
export function openJson<T>(sodium: Sodium, key: Uint8Array, blob: Uint8Array, schema: z.ZodType<T>, currentVersion: number, migrations: PayloadMigrations, context: AdContext, bindingId: string): T;
```

- `buildAdditionalData` = `utf8(context) || AD_SEPARATOR || utf8(bindingId)`.
- `sealBlob` = `FORMAT_VERSION_SEALED_BLOB || nonce || aeadEncrypt(plaintext, AD, nonce, key)`,
  nonce = `sodium.randomBytes(AEAD_NONCE_BYTES)`. Key length ≠ `KEY_BYTES` →
  `InvalidKeyLengthError`.
- `openBlob`: length < `1 + AEAD_NONCE_BYTES + AEAD_TAG_BYTES` → `MalformedPayloadError`; byte 0
  ≠ version → `UnsupportedFormatError`; AEAD failure → the port's `DecryptionError`.
- `wrapKey`: `keyToWrap.length !== KEY_BYTES` → `InvalidKeyLengthError`. `unwrapKey`: opened
  length ≠ `KEY_BYTES` → `MalformedPayloadError`.
- `sealJson` = `sealBlob(padIso7816(utf8(JSON.stringify(value)), PADDING_BLOCK_BYTES))`.
- `openJson`: open → unpad → `JSON.parse` (failure → `MalformedPayloadError`) → must be a plain
  object with an integer `schemaVersion` (else `MalformedPayloadError`) → if
  `schemaVersion > currentVersion` → `UnsupportedFormatError` → apply `migrations.get(v)` for
  `v = schemaVersion … currentVersion − 1` in order (a missing step → `UnsupportedFormatError`) →
  `schema.safeParse` (failure → `MalformedPayloadError`) → return data.

## 8. `stream.ts`

Types (exact fields; tests compare with `toEqual` on the full object unless they say
`toMatchObject`):

```ts
export type StreamHeader = { readonly version: number; readonly chunkSizeExponent: number; readonly noncePrefix: Uint8Array };
export type StreamLayout = { readonly plaintextSize: number; readonly chunkSizeExponent: number; readonly chunkSize: number; readonly chunkCount: number; readonly chunkCiphertextSize: number; readonly ciphertextSize: number };
export type ChunkRange = { readonly firstChunk: number; readonly lastChunk: number };
```

`chunkSize = STREAM_CHUNK_SIZE_BASE ** chunkSizeExponent`. All functions from `crypto.md` §7.3,
with these exact behaviors:

| Function | Behavior and errors |
|---|---|
| `createStreamHeader(sodium)` | `STREAM_MAGIC || STREAM_VERSION || STREAM_CHUNK_SIZE_EXPONENT || 0x00 0x00 || randomBytes(16)` |
| `parseStreamHeader(bytes)` | length ≠ 24 or magic mismatch → `MalformedObjectError`; version ≠ 1 → `UnsupportedFormatError`; reserved bytes non-zero → `MalformedObjectError`; exponent outside `[MIN, MAX]` → `MalformedObjectError`. Returns `{ version, chunkSizeExponent, noncePrefix }` (prefix copied) |
| `streamLayout(P, e)` | `P` not a non-negative safe integer → `MalformedObjectError`. `C = 2 ** e`, `n = max(1, ceil(P / C))`, `ciphertextSize = 24 + P + 16n`, `chunkCiphertextSize = C + 16` |
| `layoutFromCiphertextSize(S, e)` | `n = ceil((S − 24) / (C + 16))`, `P = S − 24 − 16n`; `S < STREAM_MIN_OBJECT_BYTES`, `n < 1` or `P < 0` → `MalformedObjectError`; result must round-trip through `streamLayout(P, e)` else `MalformedObjectError` |
| `chunkRangeForPlaintextRange(layout, start, end)` | requires `0 ≤ start ≤ end < P` (integers) else `MalformedObjectError`; `{ firstChunk: floor(start / C), lastChunk: floor(end / C) }` |
| `streamNonce(header, index, isFinal)` | `noncePrefix || uint56be(index) || (isFinal ? FINAL : NOT_FINAL)`; index rules from `writeUint56BigEndian` |
| `encryptChunk(...)` | parses the header; non-final chunk length ≠ `C` → `InvalidChunkError`; final chunk length > `C` → `InvalidChunkError`; `aeadEncrypt(plaintext, header, nonce, key)` |
| `decryptChunk(...)` | ciphertext length outside `[16, C + 16]` → `MalformedObjectError`; AEAD failure → `DecryptionError` |
| `createStreamEncryptor(sodium, key, P)` | builds header + layout; `encryptNext(chunk)` encrypts chunk `i` with `isFinal = (i === n − 1)`; expects length `C` for non-final, `P − C·(n − 1)` for the final chunk, else `InvalidChunkError`; calling after the last chunk → `InvalidChunkError`; `isDone()` true after the last chunk |
| `createStreamDecryptor(sodium, key, header, expectedP)` | layout from `expectedP` and the header's exponent; `decryptNext(ct)` decrypts index `i` with `isFinal = (i === n − 1)`; expects ciphertext length `C + 16` for non-final, final length = `P − C·(n − 1) + 16`, else `MalformedObjectError`; after the last chunk → `InvalidChunkError`; `finish()` throws `TruncatedObjectError` if `i < n` |

The header is the AEAD additional data for every chunk (crypto.md §7.1).

## 9. Recovery words

`wordlist-en.ts`: `export const WORDLIST_EN: readonly string[] = Object.freeze([...])`, the 2048
words of `https://raw.githubusercontent.com/bitcoin/bips/master/bip-0039/english.txt` in file
order. Before committing: verify 2048 lines, all lowercase ASCII, unique; record the URL, the
fetch date and the file's SHA-256 in `docs/facts.md` as row `F-CRY-04`.

`recovery-words.ts`:

```ts
export function encodeRecoveryKey(sodium: Sodium, recoveryKey: Uint8Array): readonly string[];
export function decodeRecoveryWords(sodium: Sodium, words: readonly string[]): Uint8Array;
export function normalizeRecoveryInput(input: string): readonly string[];
```

- Encode: key length ≠ 32 → `InvalidKeyLengthError`; checksum = first byte of
  `genericHash(GENERIC_HASH_BYTES, key)`; bit string = 256 key bits + 8 checksum bits, most
  significant bit first; 24 indexes of 11 bits.
- `normalizeRecoveryInput`: NFKC, lowercase, split on any whitespace, drop empty parts.
- Decode: count ≠ 24 → `InvalidRecoveryWordsError(null, invalidRecoveryWordCount)`; unknown word
  at position `k` (0-based) → `InvalidRecoveryWordsError(k, unknownRecoveryWord(k + 1))`; checksum
  mismatch → `InvalidRecoveryWordsError(null, recoveryChecksumMismatch)`.
- Word lookup through a `Map<string, number>` built once at module load from `WORDLIST_EN`.

## 10. `share-payload.ts`

```ts
export function buildSharePayload(collectionId: string, collectionKey: Uint8Array): Uint8Array;
export function openSharePayload(sodium: Sodium, sealed: Uint8Array, publicKey: Uint8Array, privateKey: Uint8Array, expectedCollectionId: string): Uint8Array;
export function publicKeyFingerprint(sodium: Sodium, publicKey: Uint8Array): string;
```

- Payload = `FORMAT_VERSION_SHARE_PAYLOAD || uint8(idBytes.length) || idBytes || collectionKey`.
  `collectionId` must match `ID_REGEX` from `@kodakiko/contract` (else `MalformedPayloadError`);
  key length ≠ 32 → `InvalidKeyLengthError`. Sealing is the caller's job.
- `openSharePayload`: `boxSealOpen` → version byte ≠ 1 → `UnsupportedFormatError`; length or id
  length inconsistent → `MalformedPayloadError`; embedded id ≠ expected →
  `DecryptionError(shareCollectionMismatch)`; returns the 32-byte key.
- `publicKeyFingerprint`: first `FINGERPRINT_BYTES` of `genericHash(GENERIC_HASH_BYTES, publicKey)`
  as lowercase hex, `FINGERPRINT_GROUPS` groups of `FINGERPRINT_GROUP_CHARS` characters joined by
  single spaces (`"a1b2 c3d4 e5f6 0718"`), crypto.md
  §8.1.

## 11. Test helpers

`src/crypto/deterministic-bytes.ts` (in `src/` because the on-device vector check of spec 07a uses
it; it is never used to make real keys):

```ts
export function counterBytes(seed: number, length: number): Uint8Array;
export type RandomSource = (length: number) => Uint8Array;
export function createCounterRandom(seed: number): RandomSource;
export function withRandomSource(sodium: Sodium, random: RandomSource): Sodium;
```

`withRandomSource` returns a copy of the port whose `randomBytes` is `random`.

- `counterBytes`: byte `i` = `(seed + i) & BYTE_MASK`.
- `createCounterRandom`: a stateful source; successive calls continue the sequence
  (`(seed + totalBytesSoFar + i) & BYTE_MASK`), so two random draws in one case differ.

`test/sodium-node.ts`: `export async function createNodeSodium(random?: RandomSource): Promise<Sodium>`.
`import sodium from 'libsodium-wrappers-sumo'`, `await sodium.ready`, map every port method per
the `crypto.md` §2 table (`crypto_pwhash` with `sodium.crypto_pwhash_ALG_ARGON2ID13`;
`crypto_box_keypair` result mapped to `{ publicKey, privateKey }`). `randomBytes` uses `random`
when given, else `sodium.randombytes_buf`. Every failure of `aeadDecrypt` and `boxSealOpen` is
caught and rethrown as `DecryptionError(CRYPTO_TEXT.decryptionFailed)`, mirroring the real
adapters (spec 07a). Test files may use numeric literals (the lint rule excludes tests).

## 12. Vectors

`scripts/generate-vectors.ts`, run from `packages/core` with `node scripts/generate-vectors.ts`
(Node ≥ 22.18 strips types). It imports only `src/crypto/*.ts` and `test/sodium-node.ts`, writes 7
files to `packages/core/vectors/` (2-space JSON, trailing newline), and contains no assertions.
`vectors/index.ts` imports the 7 JSON files and exports `CRYPTO_VECTORS` (typed with the shapes
below), so Node tests and the apps load the same data.

`src/crypto/vector-check.ts`:

```ts
export type VectorCaseResult = { readonly file: string; readonly name: string; readonly passed: boolean; readonly durationMs: number };
export async function checkVectors(sodium: Sodium, vectors: CryptoVectors, now: () => number): Promise<readonly VectorCaseResult[]>;
```

It recomputes every case of every file exactly as the generator did (inputs from `counterBytes`,
randomness via `withRandomSource(sodium, createCounterRandom(randomSeed))`) and compares. Pure:
the vectors and the clock are passed in. `CryptoVectors` and the per-file case types live in
`src/crypto/vector-types.ts` (in `src`, so `vectors/index.ts` imports them from
`../src/crypto/vector-types.ts`, never the other way round). Case names: `<file>#<index>`.

Every file: `{ "format": 1, "cases": [...] }`; binary values are lowercase hex strings.

Inputs come from `counterBytes(seed, length)`; whenever the code under test draws randomness, the
case uses `createNodeSodium(createCounterRandom(randomSeed))` and stores `randomSeed`.

| File | Cases (exact) | Fields |
|---|---|---|
| `argon2id.json` | 2 cases: password `passwordToBytes("correct horse battery staple")` and `passwordToBytes("e\u0301t\u00e9")`; salt `counterBytes(1, 16)` / `counterBytes(2, 16)`; `opsLimit 2`, `memLimitBytes 8388608`, `outputLength 32` | `password` (hex), `salt`, `opsLimit`, `memLimitBytes`, `output` |
| `kdf.json` | 6 cases: (login,1), (recovery,1), (media,1), (media,2), (link,1), (link,2); key `counterBytes(3, 32)` | `context`, `subkeyId`, `key`, `subkey` |
| `sealed-blob.json` | 13 cases, one per `AD_CONTEXTS` key; key `counterBytes(4, 32)`; plaintext `counterBytes(5, 40)`; bindingId `"AAAAAAAAAAAAAAAAAAAAAA"`; `randomSeed 6` | `contextKey`, `context`, `bindingId`, `key`, `plaintext`, `randomSeed`, `blob` |
| `padding.json` | lengths 0, 1, 255, 256, 257, 512; input `counterBytes(7, length)`; block 256 | `input`, `padded` |
| `stream.json` | plaintext sizes 0, 1, 1048575, 1048576, 1048577, 3145728; key `counterBytes(8, 32)`; plaintext `counterBytes(9, size)`; `randomSeed 10` (header prefix); encrypted with `createStreamEncryptor` | `plaintextSize`, `key`, `randomSeed`, `header`, `chunkCount`, `ciphertextSize`, `chunkDigests` (list: `genericHash(32, chunk)` hex of each ciphertext chunk, so the JSON stays small and the check needs no extra hash library), `firstChunkPrefix` (first 32 bytes of chunk 0 in hex) |
| `recovery-words.json` | 3 cases: keys `counterBytes(11, 32)`, `counterBytes(12, 32)`, all-`0xff` 32 bytes | `recoveryKey`, `words` |
| `share-payload.json` | 2 cases: ids `"AAAAAAAAAAAAAAAAAAAAAA"`, `"zzzzzzzzzzzzzzzzzzzzzz"`; key `counterBytes(13, 32)` | `collectionId`, `collectionKey`, `payload` |

No `node:crypto` anywhere in this package; digests use the port's `genericHash`.

## 13. Tests

`test/vectors.test.ts`: `checkVectors(await createNodeSodium(), CRYPTO_VECTORS, performance.now)`
→ every result `passed`; and a copy of `CRYPTO_VECTORS` with the first byte of `kdf.json` case 0's
`subkey` changed → exactly that case reports `passed: false`, all others `true` (proves the checker
can fail). Running the generator again must change nothing; the test is what proves
it.

`test/crypto.test.ts` (real randomness via `createNodeSodium()` unless stated):

1. Round-trips:
   - `sealBlob`/`openBlob` for every AD context with plaintext lengths 0, 100, 100,000;
   - `sealJson`/`openJson` with schema `z.object({ schemaVersion: z.literal(2), value: z.string() })`,
     `currentVersion 2`; a version-1 payload `{ schemaVersion: 1, text: "x" }` sealed with
     `sealJson` opens through a migration `1 → { schemaVersion: 2, value: raw.text }`;
   - `wrapKey`/`unwrapKey`;
   - stream: plaintext of `2.5 × C` bytes through `createStreamEncryptor` then
     `createStreamDecryptor` + `finish()`; plus random access: decrypting single chunks by index
     with `decryptChunk` for windows `[0, 0]`, `[P − 1, P − 1]`, `[C − 10, C + 10]` and slicing,
     each equal to the original bytes;
   - recovery words encode → decode; `normalizeRecoveryInput("  Abandon\tABILITY  ")` →
     `["abandon", "ability"]`;
   - share payload build → `boxSeal` → `openSharePayload` with a fresh keypair;
     `publicKeyFingerprint` matches `/^[0-9a-f]{4}( [0-9a-f]{4}){3}$/`.
2. Errors (assert the exact class):
   - one flipped bit in a sealed blob → `DecryptionError`; wrong `bindingId` → `DecryptionError`;
     byte 0 = `0x02` → `UnsupportedFormatError`; 40-byte blob → `MalformedPayloadError`;
   - `openJson` of `schemaVersion: 3` with `currentVersion 2` → `UnsupportedFormatError`; missing
     migration step → `UnsupportedFormatError`; schema mismatch → `MalformedPayloadError`;
   - `unpadIso7816(Uint8Array.of(1, 2), 2)` → `MalformedPayloadError`;
   - `wrapKey` with a 31-byte key → `InvalidKeyLengthError`; `unwrapKey` of a sealed 31-byte
     plaintext → `MalformedPayloadError`;
   - `layoutFromCiphertextSize(39, 20)` → `MalformedObjectError`; `(40, 20)` →
     `{ chunkCount: 1, plaintextSize: 0 }`;
   - `parseStreamHeader` with bad magic, version `0x02`, reserved `0x0001`, exponent 30 →
     `MalformedObjectError`, `UnsupportedFormatError`, `MalformedObjectError`,
     `MalformedObjectError`;
   - encryptor: short non-final chunk → `InvalidChunkError`; extra call after done →
     `InvalidChunkError`;
   - decryptor on an object with `P = C + 5` (2 chunks): feed only chunk 0, then `finish()` →
     `TruncatedObjectError`; feed chunk 1 (21 bytes) as the first call → `MalformedObjectError`
     (length);
   - truncation attack: encrypt `P = 3C`, keep chunks 0 and 1, decrypt with `expectedP = 2C` →
     chunk 0 succeeds, chunk 1 (decrypted as final) → `DecryptionError`;
   - `writeUint56BigEndian(…, 2 ** 53)` → `InvalidChunkError`;
   - recovery: 23 words → `wordPosition === null`; word 5 replaced by `"notaword"` →
     `wordPosition === 4`; last word replaced by `WORDLIST_EN[index ^ 1]` (flips only a checksum
     bit, so the mismatch is guaranteed) → checksum error with `wordPosition === null`;
   - `openSharePayload` with a different expected id → `DecryptionError`.
3. Normalization: `passwordToBytes('e\u0301')` equals `passwordToBytes('\u00e9')`; differs from
   `passwordToBytes('e')`.

## 14. Steps

1. Add the dependencies of spec 01 §1.3 if missing (`@kodakiko/contract`, `zod`,
   `libsodium-wrappers-sumo` dev).
2. Write the files in section 1 order (text/errors/constants first).
3. Fetch and verify the word list; add `F-CRY-04` to `docs/facts.md`.
4. `node scripts/generate-vectors.ts`; inspect the files; commit them.
5. Write both test files; `pnpm --filter @kodakiko/core test` until green.
6. `pnpm verify` at the root.

## 15. Verification checklist

- [ ] `pnpm verify` passes (paste the test summary line).
- [ ] 7 vector files in `packages/core/vectors/` committed; every case replays green through `checkVectors`.
- [ ] Rerunning the generator leaves `git diff --stat` empty.
- [ ] `packages/core/src` imports only relative files, `@kodakiko/contract`, `zod`
      (`grep -rhn "from '" packages/core/src | sort -u`).
- [ ] No explanatory comments, no magic numbers outside `constants.ts` (lint proves both).
- [ ] `F-CRY-04` row in `docs/facts.md` with URL, date and SHA-256.
- [ ] Error-case list of §13.2: each observed class reported.
- [ ] Argon2id vector runtime noted (it uses 8 MiB, should be well under a second).

## 16. Done when

Checklist fully reported and `pnpm verify` green.
