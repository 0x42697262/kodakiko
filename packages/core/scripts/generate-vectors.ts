import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  AD_CONTEXTS,
  KDF_CONTEXTS,
  PADDING_BLOCK_BYTES,
  VECTOR_FORMAT,
  buildSharePayload,
  bytesToHex,
  createStreamEncryptor,
  encodeRecoveryKey,
  padIso7816,
  passwordToBytes,
  sealBlob,
  type KdfContext,
} from '../src/crypto/index.ts';
import { counterBytes, createCounterRandom } from '../src/crypto/deterministic-bytes.ts';
import { createNodeSodium } from '../test/sodium-node.ts';

const VECTORS_DIRECTORY = fileURLToPath(new URL('../vectors/', import.meta.url));
const ID_A = 'AAAAAAAAAAAAAAAAAAAAAA';
const ID_Z = 'zzzzzzzzzzzzzzzzzzzzzz';
const STREAM_PLAINTEXT_SEED = 9;
const STREAM_RANDOM_SEED = 10;
const STREAM_PREFIX_BYTES = 32;
const DIGEST_BYTES = 32;

function writeVectorFile(name: string, cases: readonly object[]): void {
  const content = `${JSON.stringify({ format: VECTOR_FORMAT, cases }, null, 2)}\n`;
  writeFileSync(`${VECTORS_DIRECTORY}${name}`, content);
}

const sodium = await createNodeSodium();

function seededSodium(seed: number) {
  return { ...sodium, randomBytes: createCounterRandom(seed) };
}

const argon2idInputs = [
  { password: passwordToBytes('correct horse battery staple'), salt: counterBytes(1, 16) },
  { password: passwordToBytes('e\u0301t\u00e9'), salt: counterBytes(2, 16) },
];
const argon2idCases = [];
for (const input of argon2idInputs) {
  const opsLimit = 2;
  const memLimitBytes = 8_388_608;
  const output = await sodium.argon2id({ ...input, opsLimit, memLimitBytes, outputLength: 32 });
  argon2idCases.push({
    password: bytesToHex(input.password),
    salt: bytesToHex(input.salt),
    opsLimit,
    memLimitBytes,
    output: bytesToHex(output),
  });
}
writeVectorFile('argon2id.json', argon2idCases);

const kdfKey = counterBytes(3, 32);
const kdfPairs: ReadonlyArray<readonly [keyof typeof KDF_CONTEXTS, number]> = [
  ['login', 1],
  ['recovery', 1],
  ['media', 1],
  ['media', 2],
  ['link', 1],
  ['link', 2],
];
writeVectorFile(
  'kdf.json',
  kdfPairs.map(([context, subkeyId]) => {
    const kdfContext: KdfContext = KDF_CONTEXTS[context];
    return {
      context,
      subkeyId,
      key: bytesToHex(kdfKey),
      subkey: bytesToHex(sodium.kdfDerive(32, subkeyId, kdfContext, kdfKey)),
    };
  }),
);

const sealedKey = counterBytes(4, 32);
const sealedPlaintext = counterBytes(5, 40);
const sealedRandomSeed = 6;
writeVectorFile(
  'sealed-blob.json',
  Object.entries(AD_CONTEXTS).map(([contextKey, context]) => {
    const seeded = seededSodium(sealedRandomSeed);
    return {
      contextKey,
      context,
      bindingId: ID_A,
      key: bytesToHex(sealedKey),
      plaintext: bytesToHex(sealedPlaintext),
      randomSeed: sealedRandomSeed,
      blob: bytesToHex(sealBlob(seeded, sealedKey, sealedPlaintext, context, ID_A)),
    };
  }),
);

writeVectorFile(
  'padding.json',
  [0, 1, 255, 256, 257, 512].map((length) => {
    const input = counterBytes(7, length);
    return {
      input: bytesToHex(input),
      padded: bytesToHex(padIso7816(input, PADDING_BLOCK_BYTES)),
    };
  }),
);

const streamKey = counterBytes(8, 32);
writeVectorFile(
  'stream.json',
  [0, 1, 1_048_575, 1_048_576, 1_048_577, 3_145_728].map((plaintextSize) => {
    const plaintext = counterBytes(STREAM_PLAINTEXT_SEED, plaintextSize);
    const encryptor = createStreamEncryptor(
      seededSodium(STREAM_RANDOM_SEED),
      streamKey,
      plaintextSize,
    );
    const { chunkSize, chunkCount, ciphertextSize } = encryptor.layout;
    const chunks: Uint8Array[] = [];
    for (let index = 0; index < chunkCount; index += 1) {
      const start = index * chunkSize;
      chunks.push(encryptor.encryptNext(plaintext.slice(start, start + chunkSize)));
    }
    return {
      plaintextSize,
      plaintextSeed: STREAM_PLAINTEXT_SEED,
      key: bytesToHex(streamKey),
      randomSeed: STREAM_RANDOM_SEED,
      header: bytesToHex(encryptor.header),
      chunkCount,
      ciphertextSize,
      chunkDigests: chunks.map((chunk) => bytesToHex(sodium.genericHash(DIGEST_BYTES, chunk))),
      firstChunkPrefix: bytesToHex((chunks[0] ?? new Uint8Array(0)).slice(0, STREAM_PREFIX_BYTES)),
    };
  }),
);

writeVectorFile(
  'recovery-words.json',
  [counterBytes(11, 32), counterBytes(12, 32), new Uint8Array(32).fill(0xff)].map(
    (recoveryKey) => ({
      recoveryKey: bytesToHex(recoveryKey),
      words: encodeRecoveryKey(sodium, recoveryKey),
    }),
  ),
);

const shareKey = counterBytes(13, 32);
writeVectorFile(
  'share-payload.json',
  [ID_A, ID_Z].map((collectionId) => ({
    collectionId,
    collectionKey: bytesToHex(shareKey),
    payload: bytesToHex(buildSharePayload(collectionId, shareKey)),
  })),
);
