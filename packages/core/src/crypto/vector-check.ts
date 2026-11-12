import { bytesEqual, bytesToHex, concatBytes, hexToBytes } from './bytes.ts';
import {
  AD_CONTEXTS,
  GENERIC_HASH_BYTES,
  HEX_CHARS_PER_BYTE,
  KDF_CONTEXTS,
  PADDING_BLOCK_BYTES,
  VECTOR_FORMAT,
} from './constants.ts';
import { counterBytes, createCounterRandom, withRandomSource } from './deterministic-bytes.ts';
import { padIso7816, unpadIso7816 } from './padding.ts';
import { decodeRecoveryWords, encodeRecoveryKey } from './recovery-words.ts';
import { openBlob, sealBlob } from './sealed-blob.ts';
import { buildSharePayload } from './share-payload.ts';
import type { Sodium } from './sodium.ts';
import { createStreamDecryptor, createStreamEncryptor } from './stream.ts';
import type {
  Argon2idCase,
  CryptoVectors,
  KdfCase,
  PaddingCase,
  RecoveryWordsCase,
  SealedBlobCase,
  SharePayloadCase,
  StreamCase,
  VectorFile,
} from './vector-types.ts';

export type VectorCaseResult = {
  readonly file: string;
  readonly name: string;
  readonly passed: boolean;
  readonly durationMs: number;
};

type CaseCheck<Case> = (sodium: Sodium, vectorCase: Case) => Promise<boolean> | boolean;

function hexEquals(bytes: Uint8Array, hex: string): boolean {
  return bytesToHex(bytes) === hex;
}

function isKey<Table extends object>(
  table: Table,
  key: string,
): key is Extract<keyof Table, string> {
  return Object.hasOwn(table, key);
}

const checkArgon2id: CaseCheck<Argon2idCase> = async (sodium, vectorCase) => {
  const output = await sodium.argon2id({
    password: hexToBytes(vectorCase.password),
    salt: hexToBytes(vectorCase.salt),
    opsLimit: vectorCase.opsLimit,
    memLimitBytes: vectorCase.memLimitBytes,
    outputLength: vectorCase.output.length / HEX_CHARS_PER_BYTE,
  });
  return hexEquals(output, vectorCase.output);
};

const checkKdf: CaseCheck<KdfCase> = (sodium, vectorCase) => {
  if (!isKey(KDF_CONTEXTS, vectorCase.context)) return false;
  const subkey = sodium.kdfDerive(
    vectorCase.subkey.length / HEX_CHARS_PER_BYTE,
    vectorCase.subkeyId,
    KDF_CONTEXTS[vectorCase.context],
    hexToBytes(vectorCase.key),
  );
  return hexEquals(subkey, vectorCase.subkey);
};

const checkSealedBlob: CaseCheck<SealedBlobCase> = (sodium, vectorCase) => {
  if (!isKey(AD_CONTEXTS, vectorCase.contextKey)) return false;
  const context = AD_CONTEXTS[vectorCase.contextKey];
  if (context !== vectorCase.context) return false;
  const seeded = withRandomSource(sodium, createCounterRandom(vectorCase.randomSeed));
  const key = hexToBytes(vectorCase.key);
  const blob = sealBlob(
    seeded,
    key,
    hexToBytes(vectorCase.plaintext),
    context,
    vectorCase.bindingId,
  );
  const opened = openBlob(sodium, key, hexToBytes(vectorCase.blob), context, vectorCase.bindingId);
  return hexEquals(blob, vectorCase.blob) && hexEquals(opened, vectorCase.plaintext);
};

const checkPadding: CaseCheck<PaddingCase> = (_sodium, vectorCase) => {
  const input = hexToBytes(vectorCase.input);
  const padded = padIso7816(input, PADDING_BLOCK_BYTES);
  return (
    hexEquals(padded, vectorCase.padded) &&
    hexEquals(unpadIso7816(hexToBytes(vectorCase.padded), PADDING_BLOCK_BYTES), vectorCase.input)
  );
};

const checkStream: CaseCheck<StreamCase> = (sodium, vectorCase) => {
  const plaintext = counterBytes(vectorCase.plaintextSeed, vectorCase.plaintextSize);
  const seeded = withRandomSource(sodium, createCounterRandom(vectorCase.randomSeed));
  const encryptor = createStreamEncryptor(seeded, hexToBytes(vectorCase.key), plaintext.length);
  const { chunkSize, chunkCount } = encryptor.layout;
  const chunks: Uint8Array[] = [];
  for (let index = 0; index < chunkCount; index += 1) {
    const start = index * chunkSize;
    chunks.push(encryptor.encryptNext(plaintext.slice(start, start + chunkSize)));
  }
  const digests = chunks.map((chunk) => bytesToHex(sodium.genericHash(GENERIC_HASH_BYTES, chunk)));
  const prefixLength = vectorCase.firstChunkPrefix.length / HEX_CHARS_PER_BYTE;
  const firstChunk = chunks[0] ?? new Uint8Array(0);
  const decryptor = createStreamDecryptor(
    sodium,
    hexToBytes(vectorCase.key),
    encryptor.header,
    plaintext.length,
  );
  const decrypted = chunks.map((chunk) => decryptor.decryptNext(chunk));
  decryptor.finish();
  return (
    bytesEqual(concatBytes(...decrypted), plaintext) &&
    hexEquals(encryptor.header, vectorCase.header) &&
    chunkCount === vectorCase.chunkCount &&
    encryptor.layout.ciphertextSize === vectorCase.ciphertextSize &&
    digests.length === vectorCase.chunkDigests.length &&
    digests.every((digest, index) => digest === vectorCase.chunkDigests[index]) &&
    hexEquals(firstChunk.slice(0, prefixLength), vectorCase.firstChunkPrefix)
  );
};

const checkRecoveryWords: CaseCheck<RecoveryWordsCase> = (sodium, vectorCase) => {
  const recoveryKey = hexToBytes(vectorCase.recoveryKey);
  const words = encodeRecoveryKey(sodium, recoveryKey);
  return (
    words.length === vectorCase.words.length &&
    words.every((word, index) => word === vectorCase.words[index]) &&
    hexEquals(decodeRecoveryWords(sodium, vectorCase.words), vectorCase.recoveryKey)
  );
};

const checkSharePayload: CaseCheck<SharePayloadCase> = (_sodium, vectorCase) =>
  hexEquals(
    buildSharePayload(vectorCase.collectionId, hexToBytes(vectorCase.collectionKey)),
    vectorCase.payload,
  );

async function runFile<Case>(
  sodium: Sodium,
  file: string,
  vectorFile: VectorFile<Case>,
  check: CaseCheck<Case>,
  now: () => number,
): Promise<readonly VectorCaseResult[]> {
  const results: VectorCaseResult[] = [];
  for (const [index, vectorCase] of vectorFile.cases.entries()) {
    const startedAt = now();
    let passed = false;
    if (vectorFile.format === VECTOR_FORMAT) {
      try {
        passed = await check(sodium, vectorCase);
      } catch {
        passed = false;
      }
    }
    results.push({ file, name: `${file}#${index}`, passed, durationMs: now() - startedAt });
  }
  return results;
}

export async function checkVectors(
  sodium: Sodium,
  vectors: CryptoVectors,
  now: () => number,
): Promise<readonly VectorCaseResult[]> {
  return [
    ...(await runFile(sodium, 'argon2id.json', vectors.argon2id, checkArgon2id, now)),
    ...(await runFile(sodium, 'kdf.json', vectors.kdf, checkKdf, now)),
    ...(await runFile(sodium, 'sealed-blob.json', vectors.sealedBlob, checkSealedBlob, now)),
    ...(await runFile(sodium, 'padding.json', vectors.padding, checkPadding, now)),
    ...(await runFile(sodium, 'stream.json', vectors.stream, checkStream, now)),
    ...(await runFile(
      sodium,
      'recovery-words.json',
      vectors.recoveryWords,
      checkRecoveryWords,
      now,
    )),
    ...(await runFile(sodium, 'share-payload.json', vectors.sharePayload, checkSharePayload, now)),
  ];
}
