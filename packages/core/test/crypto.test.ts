import { describe, expect, it } from 'vitest';
import { z } from 'zod';
import {
  AD_CONTEXTS,
  CRYPTO_TEXT,
  type AdContext,
  DecryptionError,
  InvalidChunkError,
  InvalidKeyLengthError,
  InvalidRecoveryWordsError,
  MalformedObjectError,
  MalformedPayloadError,
  PADDING_BLOCK_BYTES,
  STREAM_HEADER_BYTES,
  STREAM_MAGIC,
  TruncatedObjectError,
  UnsupportedFormatError,
  WORDLIST_EN,
  buildSharePayload,
  bytesEqual,
  chunkRangeForPlaintextRange,
  concatBytes,
  createStreamHeader,
  encryptChunk,
  streamLayout,
  utf8Encode,
  createStreamDecryptor,
  createStreamEncryptor,
  decodeRecoveryWords,
  decryptChunk,
  encodeRecoveryKey,
  layoutFromCiphertextSize,
  normalizeRecoveryInput,
  openBlob,
  openJson,
  openSharePayload,
  padIso7816,
  parseStreamHeader,
  passwordToBytes,
  publicKeyFingerprint,
  sealBlob,
  sealJson,
  unpadIso7816,
  unwrapKey,
  wrapKey,
  writeUint56BigEndian,
  type PayloadMigrations,
  type Sodium,
} from '../src/crypto/index.ts';
import {
  counterBytes,
  createCounterRandom,
  withRandomSource,
} from '../src/crypto/deterministic-bytes.ts';
import { createNodeSodium } from './sodium-node.ts';

const CHUNK = 2 ** 20;
const KEY_LENGTH = 32;
const BINDING_ID = 'AAAAAAAAAAAAAAAAAAAAAA';
const OTHER_BINDING_ID = 'BBBBBBBBBBBBBBBBBBBBBB';

const key = counterBytes(1, KEY_LENGTH);

function encryptWholeObject(sodium: Sodium, plaintext: Uint8Array) {
  const encryptor = createStreamEncryptor(sodium, key, plaintext.length);
  const chunks: Uint8Array[] = [];
  for (let index = 0; index < encryptor.layout.chunkCount; index += 1) {
    const start = index * CHUNK;
    chunks.push(
      encryptor.encryptNext(plaintext.slice(start, Math.min(start + CHUNK, plaintext.length))),
    );
  }
  return { header: encryptor.header, chunks, layout: encryptor.layout, encryptor };
}

function sealPayload(
  sodium: Sodium,
  sealKey: Uint8Array,
  value: { readonly schemaVersion: number } & Readonly<Record<string, unknown>>,
  context: AdContext,
  bindingId: string,
): Uint8Array {
  return sealJson(sodium, sealKey, value, context, bindingId);
}

function catchError(action: () => unknown): unknown {
  try {
    action();
  } catch (error) {
    return error;
  }
  return undefined;
}

describe('round trips', () => {
  it('sealBlob/openBlob for every AD context and several lengths', async () => {
    const sodium = await createNodeSodium();
    for (const context of Object.values(AD_CONTEXTS)) {
      for (const length of [0, 100, 100_000]) {
        const plaintext = counterBytes(3, length);
        const blob = sealBlob(sodium, key, plaintext, context, BINDING_ID);
        expect(bytesEqual(openBlob(sodium, key, blob, context, BINDING_ID), plaintext)).toBe(true);
      }
    }
  });

  it('has 13 AD contexts', () => {
    expect(Object.keys(AD_CONTEXTS)).toHaveLength(13);
  });

  it('sealJson/openJson and migration from version 1', async () => {
    const sodium = await createNodeSodium();
    const schema = z.object({ schemaVersion: z.literal(2), value: z.string() });
    const migrations: PayloadMigrations = new Map([
      [1, (raw) => ({ schemaVersion: 2, value: raw['text'] })],
    ]);
    const current = sealPayload(
      sodium,
      key,
      { schemaVersion: 2, value: 'hello' },
      AD_CONTEXTS.entryMetadata,
      BINDING_ID,
    );
    expect(
      openJson(sodium, key, current, schema, 2, migrations, AD_CONTEXTS.entryMetadata, BINDING_ID),
    ).toEqual({
      schemaVersion: 2,
      value: 'hello',
    });
    const old = sealPayload(
      sodium,
      key,
      { schemaVersion: 1, text: 'x' },
      AD_CONTEXTS.entryMetadata,
      BINDING_ID,
    );
    expect(
      openJson(sodium, key, old, schema, 2, migrations, AD_CONTEXTS.entryMetadata, BINDING_ID),
    ).toEqual({
      schemaVersion: 2,
      value: 'x',
    });
  });

  it('wrapKey/unwrapKey', async () => {
    const sodium = await createNodeSodium();
    const inner = counterBytes(5, KEY_LENGTH);
    const wrapped = wrapKey(sodium, key, inner, AD_CONTEXTS.collectionKeyByMaster, BINDING_ID);
    expect(
      bytesEqual(
        unwrapKey(sodium, key, wrapped, AD_CONTEXTS.collectionKeyByMaster, BINDING_ID),
        inner,
      ),
    ).toBe(true);
  });

  it('stream encryptor to decryptor with 2.5 chunks', async () => {
    const sodium = await createNodeSodium();
    const plaintext = counterBytes(7, 2.5 * CHUNK);
    const { header, chunks, layout, encryptor } = encryptWholeObject(sodium, plaintext);
    expect(encryptor.isDone()).toBe(true);
    expect(layout.chunkCount).toBe(3);
    const decryptor = createStreamDecryptor(sodium, key, header, plaintext.length);
    const decrypted = chunks.map((chunk) => decryptor.decryptNext(chunk));
    decryptor.finish();
    expect(bytesEqual(concatBytes(...decrypted), plaintext)).toBe(true);
  });

  it('stream random access by chunk index', async () => {
    const sodium = await createNodeSodium();
    const plaintext = counterBytes(7, 2.5 * CHUNK);
    const { header, chunks, layout } = encryptWholeObject(sodium, plaintext);
    const last = plaintext.length - 1;
    const windows: ReadonlyArray<readonly [number, number]> = [
      [0, 0],
      [last, last],
      [CHUNK - 10, CHUNK + 10],
    ];
    for (const [start, end] of windows) {
      const range = chunkRangeForPlaintextRange(layout, start, end);
      const parts: Uint8Array[] = [];
      for (let index = range.firstChunk; index <= range.lastChunk; index += 1) {
        const ciphertext = chunks[index];
        if (ciphertext === undefined) throw new Error('missing chunk');
        parts.push(
          decryptChunk(sodium, key, header, index, index === layout.chunkCount - 1, ciphertext),
        );
      }
      const joined = concatBytes(...parts);
      const offset = start - range.firstChunk * CHUNK;
      expect(
        bytesEqual(
          joined.slice(offset, offset + (end - start + 1)),
          plaintext.slice(start, end + 1),
        ),
      ).toBe(true);
    }
  });

  it('recovery words encode then decode, and normalize input', async () => {
    const sodium = await createNodeSodium();
    const recoveryKey = counterBytes(11, KEY_LENGTH);
    const words = encodeRecoveryKey(sodium, recoveryKey);
    expect(words).toHaveLength(24);
    expect(bytesEqual(decodeRecoveryWords(sodium, words), recoveryKey)).toBe(true);
    expect(normalizeRecoveryInput('  Abandon\tABILITY  ')).toEqual(['abandon', 'ability']);
  });

  it('share payload build, boxSeal, open; fingerprint format', async () => {
    const sodium = await createNodeSodium();
    const keypair = sodium.boxKeypair();
    const collectionKey = counterBytes(13, KEY_LENGTH);
    const sealed = sodium.boxSeal(buildSharePayload(BINDING_ID, collectionKey), keypair.publicKey);
    const opened = openSharePayload(
      sodium,
      sealed,
      keypair.publicKey,
      keypair.privateKey,
      BINDING_ID,
    );
    expect(bytesEqual(opened, collectionKey)).toBe(true);
    expect(publicKeyFingerprint(sodium, keypair.publicKey)).toMatch(
      /^[0-9a-f]{4}( [0-9a-f]{4}){3}$/,
    );
  });
});

describe('errors', () => {
  it('sealed blob failures', async () => {
    const sodium = await createNodeSodium();
    const context = AD_CONTEXTS.entryMetadata;
    const blob = sealBlob(sodium, key, counterBytes(1, 50), context, BINDING_ID);

    const flipped = blob.slice();
    flipped[flipped.length - 1] = (flipped[flipped.length - 1] ?? 0) ^ 1;
    expect(catchError(() => openBlob(sodium, key, flipped, context, BINDING_ID))).toBeInstanceOf(
      DecryptionError,
    );

    expect(catchError(() => openBlob(sodium, key, blob, context, OTHER_BINDING_ID))).toBeInstanceOf(
      DecryptionError,
    );

    const wrongVersion = blob.slice();
    wrongVersion[0] = 0x02;
    expect(
      catchError(() => openBlob(sodium, key, wrongVersion, context, BINDING_ID)),
    ).toBeInstanceOf(UnsupportedFormatError);

    expect(
      catchError(() => openBlob(sodium, key, new Uint8Array(40), context, BINDING_ID)),
    ).toBeInstanceOf(MalformedPayloadError);
  });

  it('openJson failures', async () => {
    const sodium = await createNodeSodium();
    const context = AD_CONTEXTS.userSettings;
    const schema = z.object({ schemaVersion: z.literal(2), value: z.string() });
    const newer = sealPayload(sodium, key, { schemaVersion: 3 }, context, BINDING_ID);
    expect(
      catchError(() => openJson(sodium, key, newer, schema, 2, new Map(), context, BINDING_ID)),
    ).toBeInstanceOf(UnsupportedFormatError);
    const old = sealPayload(sodium, key, { schemaVersion: 1, text: 'x' }, context, BINDING_ID);
    expect(
      catchError(() => openJson(sodium, key, old, schema, 2, new Map(), context, BINDING_ID)),
    ).toBeInstanceOf(UnsupportedFormatError);
    const mismatch = sealPayload(sodium, key, { schemaVersion: 2, value: 5 }, context, BINDING_ID);
    expect(
      catchError(() => openJson(sodium, key, mismatch, schema, 2, new Map(), context, BINDING_ID)),
    ).toBeInstanceOf(MalformedPayloadError);
  });

  it('padding, key wrapping and key length failures', async () => {
    const sodium = await createNodeSodium();
    expect(catchError(() => unpadIso7816(Uint8Array.of(1, 2), 2))).toBeInstanceOf(
      MalformedPayloadError,
    );
    expect(
      catchError(() =>
        wrapKey(sodium, key, new Uint8Array(31), AD_CONTEXTS.mediaKeyByEntry, BINDING_ID),
      ),
    ).toBeInstanceOf(InvalidKeyLengthError);
    const shortBlob = sealBlob(
      sodium,
      key,
      new Uint8Array(31),
      AD_CONTEXTS.mediaKeyByEntry,
      BINDING_ID,
    );
    expect(
      catchError(() => unwrapKey(sodium, key, shortBlob, AD_CONTEXTS.mediaKeyByEntry, BINDING_ID)),
    ).toBeInstanceOf(MalformedPayloadError);
  });

  it('padding round trip adds at least one byte', () => {
    expect(padIso7816(new Uint8Array(PADDING_BLOCK_BYTES), PADDING_BLOCK_BYTES)).toHaveLength(
      2 * PADDING_BLOCK_BYTES,
    );
    expect(
      unpadIso7816(padIso7816(counterBytes(1, 10), PADDING_BLOCK_BYTES), PADDING_BLOCK_BYTES),
    ).toEqual(counterBytes(1, 10));
  });

  it('stream layout and header failures', async () => {
    const sodium = await createNodeSodium();
    expect(catchError(() => layoutFromCiphertextSize(39, 20))).toBeInstanceOf(MalformedObjectError);
    expect(layoutFromCiphertextSize(40, 20)).toEqual({
      plaintextSize: 0,
      chunkSizeExponent: 20,
      chunkSize: 1_048_576,
      chunkCount: 1,
      chunkCiphertextSize: 1_048_592,
      ciphertextSize: 40,
    });

    const { header } = encryptWholeObject(sodium, new Uint8Array(0));
    expect(header).toHaveLength(STREAM_HEADER_BYTES);
    expect(bytesEqual(header.slice(0, STREAM_MAGIC.length), STREAM_MAGIC)).toBe(true);

    const badMagic = header.slice();
    badMagic[0] = 0;
    expect(catchError(() => parseStreamHeader(badMagic))).toBeInstanceOf(MalformedObjectError);
    const badVersion = header.slice();
    badVersion[4] = 0x02;
    expect(catchError(() => parseStreamHeader(badVersion))).toBeInstanceOf(UnsupportedFormatError);
    const badReserved = header.slice();
    badReserved[7] = 0x01;
    expect(catchError(() => parseStreamHeader(badReserved))).toBeInstanceOf(MalformedObjectError);
    const badExponent = header.slice();
    badExponent[5] = 30;
    expect(catchError(() => parseStreamHeader(badExponent))).toBeInstanceOf(MalformedObjectError);
  });

  it('stream encryptor failures', async () => {
    const sodium = await createNodeSodium();
    const short = createStreamEncryptor(sodium, key, CHUNK + 5);
    expect(catchError(() => short.encryptNext(new Uint8Array(CHUNK - 1)))).toBeInstanceOf(
      InvalidChunkError,
    );

    const complete = createStreamEncryptor(sodium, key, 3);
    complete.encryptNext(new Uint8Array(3));
    expect(complete.isDone()).toBe(true);
    expect(catchError(() => complete.encryptNext(new Uint8Array(3)))).toBeInstanceOf(
      InvalidChunkError,
    );
  });

  it('stream decryptor truncation and length failures', async () => {
    const sodium = await createNodeSodium();
    const plaintext = counterBytes(2, CHUNK + 5);
    const { header, chunks } = encryptWholeObject(sodium, plaintext);
    const first = chunks[0] as Uint8Array;
    const second = chunks[1] as Uint8Array;

    const truncated = createStreamDecryptor(sodium, key, header, plaintext.length);
    truncated.decryptNext(first);
    expect(catchError(() => truncated.finish())).toBeInstanceOf(TruncatedObjectError);

    const outOfOrder = createStreamDecryptor(sodium, key, header, plaintext.length);
    expect(second).toHaveLength(21);
    expect(catchError(() => outOfOrder.decryptNext(second))).toBeInstanceOf(MalformedObjectError);
  });

  it('stream truncation attack at a chunk boundary', async () => {
    const sodium = await createNodeSodium();
    const { header, chunks } = encryptWholeObject(sodium, counterBytes(4, 3 * CHUNK));
    const decryptor = createStreamDecryptor(sodium, key, header, 2 * CHUNK);
    decryptor.decryptNext(chunks[0] as Uint8Array);
    expect(catchError(() => decryptor.decryptNext(chunks[1] as Uint8Array))).toBeInstanceOf(
      DecryptionError,
    );
  });

  it('writeUint56BigEndian rejects 2 ** 53', () => {
    expect(catchError(() => writeUint56BigEndian(new Uint8Array(8), 0, 2 ** 53))).toBeInstanceOf(
      InvalidChunkError,
    );
  });

  it('recovery word failures', async () => {
    const sodium = await createNodeSodium();
    const words = encodeRecoveryKey(sodium, counterBytes(12, KEY_LENGTH));

    const countError = catchError(() => decodeRecoveryWords(sodium, words.slice(0, 23)));
    expect(countError).toBeInstanceOf(InvalidRecoveryWordsError);
    expect((countError as InvalidRecoveryWordsError).wordPosition).toBeNull();
    expect((countError as InvalidRecoveryWordsError).message).toBe(
      CRYPTO_TEXT.invalidRecoveryWordCount,
    );

    const unknown = words.slice();
    unknown[4] = 'notaword';
    const unknownError = catchError(() => decodeRecoveryWords(sodium, unknown));
    expect(unknownError).toBeInstanceOf(InvalidRecoveryWordsError);
    expect((unknownError as InvalidRecoveryWordsError).wordPosition).toBe(4);
    expect((unknownError as InvalidRecoveryWordsError).message).toBe(
      CRYPTO_TEXT.unknownRecoveryWord(5),
    );

    const checksum = words.slice();
    const lastIndex = WORDLIST_EN.indexOf(words[23] as string);
    checksum[23] = WORDLIST_EN[lastIndex ^ 1] as string;
    const checksumError = catchError(() => decodeRecoveryWords(sodium, checksum));
    expect(checksumError).toBeInstanceOf(InvalidRecoveryWordsError);
    expect((checksumError as InvalidRecoveryWordsError).wordPosition).toBeNull();
    expect((checksumError as InvalidRecoveryWordsError).message).toBe(
      CRYPTO_TEXT.recoveryChecksumMismatch,
    );
  });

  it('openSharePayload with a different expected id', async () => {
    const sodium = await createNodeSodium();
    const keypair = sodium.boxKeypair();
    const sealed = sodium.boxSeal(
      buildSharePayload(BINDING_ID, counterBytes(13, KEY_LENGTH)),
      keypair.publicKey,
    );
    expect(
      catchError(() =>
        openSharePayload(sodium, sealed, keypair.publicKey, keypair.privateKey, OTHER_BINDING_ID),
      ),
    ).toBeInstanceOf(DecryptionError);
  });
});

describe('review additions', () => {
  it('BOM-prefixed share id is refused', async () => {
    const sodium = await createNodeSodium();
    const keypair = sodium.boxKeypair();
    const idBytes = concatBytes(Uint8Array.of(0xef, 0xbb, 0xbf), utf8Encode(BINDING_ID));
    const payload = concatBytes(
      Uint8Array.of(1, idBytes.length),
      idBytes,
      counterBytes(13, KEY_LENGTH),
    );
    const sealed = sodium.boxSeal(payload, keypair.publicKey);
    expect(
      catchError(() =>
        openSharePayload(sodium, sealed, keypair.publicKey, keypair.privateKey, BINDING_ID),
      ),
    ).toBeInstanceOf(DecryptionError);
  });

  it('mutating the exposed encryptor header does not change later chunks', async () => {
    const plaintext = counterBytes(2, CHUNK + 5);
    const reference = createStreamEncryptor(
      await createNodeSodium(createCounterRandom(1)),
      key,
      plaintext.length,
    );
    const mutated = createStreamEncryptor(
      await createNodeSodium(createCounterRandom(1)),
      key,
      plaintext.length,
    );
    expect(mutated.header).toEqual(reference.header);
    mutated.header.fill(0);
    const expectedFirst = reference.encryptNext(plaintext.slice(0, CHUNK));
    const expectedLast = reference.encryptNext(plaintext.slice(CHUNK));
    expect(mutated.encryptNext(plaintext.slice(0, CHUNK))).toEqual(expectedFirst);
    expect(mutated.encryptNext(plaintext.slice(CHUNK))).toEqual(expectedLast);
  });

  it('mutating the header passed to the decryptor does not change later chunks', async () => {
    const sodium = await createNodeSodium();
    const plaintext = counterBytes(2, CHUNK + 5);
    const { header, chunks } = encryptWholeObject(sodium, plaintext);
    const passed = header.slice();
    const decryptor = createStreamDecryptor(sodium, key, passed, plaintext.length);
    passed.fill(0);
    const decrypted = chunks.map((chunk) => decryptor.decryptNext(chunk));
    decryptor.finish();
    expect(bytesEqual(concatBytes(...decrypted), plaintext)).toBe(true);
  });

  it('stream chunk functions reject a 31-byte key', async () => {
    const sodium = await createNodeSodium();
    const header = createStreamHeader(sodium);
    const shortKey = new Uint8Array(31);
    expect(
      catchError(() => encryptChunk(sodium, shortKey, header, 0, true, new Uint8Array(3))),
    ).toBeInstanceOf(InvalidKeyLengthError);
    expect(
      catchError(() => decryptChunk(sodium, shortKey, header, 0, true, new Uint8Array(19))),
    ).toBeInstanceOf(InvalidKeyLengthError);
  });

  it('stream layout rejects a chunk-size exponent outside the allowed range', () => {
    expect(catchError(() => streamLayout(10, 99))).toBeInstanceOf(MalformedObjectError);
    expect(catchError(() => streamLayout(10, 9))).toBeInstanceOf(MalformedObjectError);
    expect(catchError(() => streamLayout(10, 25))).toBeInstanceOf(MalformedObjectError);
    expect(catchError(() => layoutFromCiphertextSize(40, 99))).toBeInstanceOf(MalformedObjectError);
  });

  it('unwrapKey wipes a wrong-length opened key before throwing', async () => {
    const sodium = await createNodeSodium();
    const opened: Uint8Array[] = [];
    const spying: Sodium = {
      ...sodium,
      aeadDecrypt: (...args) => {
        const result = sodium.aeadDecrypt(...args);
        opened.push(result);
        return result;
      },
    };
    const shortBlob = sealBlob(
      sodium,
      key,
      counterBytes(1, 31),
      AD_CONTEXTS.mediaKeyByEntry,
      BINDING_ID,
    );
    expect(
      catchError(() => unwrapKey(spying, key, shortBlob, AD_CONTEXTS.mediaKeyByEntry, BINDING_ID)),
    ).toBeInstanceOf(MalformedPayloadError);
    expect(opened).toHaveLength(1);
    expect(opened[0]?.every((byte) => byte === 0)).toBe(true);
  });

  it('openSharePayload wipes the opened payload and returns a separate key copy', async () => {
    const sodium = await createNodeSodium();
    const keypair = sodium.boxKeypair();
    const collectionKey = counterBytes(13, KEY_LENGTH);
    const sealed = sodium.boxSeal(buildSharePayload(BINDING_ID, collectionKey), keypair.publicKey);
    const opened: Uint8Array[] = [];
    const spying: Sodium = {
      ...sodium,
      boxSealOpen: (...args) => {
        const result = sodium.boxSealOpen(...args);
        opened.push(result);
        return result;
      },
    };
    const result = openSharePayload(
      spying,
      sealed,
      keypair.publicKey,
      keypair.privateKey,
      BINDING_ID,
    );
    expect(bytesEqual(result, collectionKey)).toBe(true);
    expect(opened[0]?.every((byte) => byte === 0)).toBe(true);
  });

  it('openSharePayload wipes the opened payload when the id does not match', async () => {
    const sodium = await createNodeSodium();
    const keypair = sodium.boxKeypair();
    const sealed = sodium.boxSeal(
      buildSharePayload(BINDING_ID, counterBytes(13, KEY_LENGTH)),
      keypair.publicKey,
    );
    const opened: Uint8Array[] = [];
    const spying: Sodium = {
      ...sodium,
      boxSealOpen: (...args) => {
        const result = sodium.boxSealOpen(...args);
        opened.push(result);
        return result;
      },
    };
    expect(
      catchError(() =>
        openSharePayload(spying, sealed, keypair.publicKey, keypair.privateKey, OTHER_BINDING_ID),
      ),
    ).toBeInstanceOf(DecryptionError);
    expect(opened[0]?.every((byte) => byte === 0)).toBe(true);
  });

  it('withRandomSource replaces only randomBytes', async () => {
    const sodium = await createNodeSodium();
    const seeded = withRandomSource(sodium, createCounterRandom(5));
    expect(seeded.randomBytes(3)).toEqual(counterBytes(5, 3));
    expect(seeded.randomBytes(3)).toEqual(counterBytes(8, 3));
    expect(seeded.genericHash).toBe(sodium.genericHash);
  });
});

describe('normalization', () => {
  it('passwordToBytes applies NFKC', () => {
    expect(passwordToBytes('e\u0301')).toEqual(passwordToBytes('\u00e9'));
    expect(passwordToBytes('e\u0301')).not.toEqual(passwordToBytes('e'));
  });

  it('passwordToBytes expands compatibility characters (NFKC, not NFC)', () => {
    expect(passwordToBytes('\uFB01')).toEqual(passwordToBytes('fi'));
  });
});
