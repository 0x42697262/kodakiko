import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import libsodium from 'libsodium-wrappers-sumo';
import * as contract from '@kodakiko/contract';
import {
  CollectionMetadataV1,
  EntryMetadataV1,
  NOTE_MAX_CHARS,
  encodeBytes,
  passwordChangeRequest,
  recoveryCompleteRequest,
  signUpRequest,
} from '@kodakiko/contract';
import { z } from 'zod';
import {
  AD_CONTEXTS,
  bytesEqual,
  bytesToHex,
  concatBytes,
  createStreamEncryptor,
  decodeRecoveryWords,
  normalizeRecoveryInput,
  passwordToBytes,
  sealJson,
  unwrapKey,
  type AdContext,
  type Sodium,
} from '../src/crypto/index.ts';
import {
  createPasswordChangeMaterial,
  createRecoveryCompletion,
  createSignUpMaterial,
  deriveLoginMaterial,
  openRecovery,
  rebuildSignUpRequest,
  type KdfParams,
  type PasswordChangeBytes,
  type RecoveryCompleteBytes,
  type SignUpRequestBytes,
  type StoredLocalAccount,
} from '../src/account/index.ts';
import { counterBytes, createCounterRandom } from '../src/crypto/deterministic-bytes.ts';
import { createNodeSodium } from '../test/sodium-node.ts';

const GOLDEN_DIRECTORY = fileURLToPath(new URL('../../../tests/fixtures/golden/', import.meta.url));
const GOLDEN_FORMAT = 1;
const GENERATOR = 'packages/core/scripts/generate-goldens.ts';
const JSON_INDENT = 2;
const KEY_BYTES = 32;
const ID_BYTES = 16;
const BOX_SEED_BYTES = 32;
const GOLDEN_OPS_LIMIT = 2;
const GOLDEN_MEM_LIMIT_BYTES = 8_388_608;
const SAMPLE_TOKEN_BYTES = 32;
const SAMPLE_WRAPPED_KEY_BYTES = 73;
const SAMPLE_BLOB_BYTES = 297;
const SAMPLE_SEALED_BOX_BYTES = 80;
const LONG_NOTE_UNIT = 'The quick brown fox jumps over the lazy dog. 0123456789\n';
const STREAM_FILE_DIRECTORY = 'crypto/stream-objects';
const STREAM_FILE_EXTENSION = '.kdks';
const SESSION_TOKEN_PREFIX = 'kdk_s_';
const INVITE_TOKEN_PREFIX = 'kdk_i_';

const PATHS = Object.freeze({
  sealedJson: 'crypto/sealed-json.json',
  streamObjects: 'crypto/stream-objects.json',
  signUp: 'account/signup.json',
  recovery: 'account/recovery.json',
  passwordChange: 'account/password-change.json',
  localAccount: 'account/local-account.json',
  dtoSamples: 'contract/dto-samples.json',
});

type Json = null | boolean | number | string | readonly Json[] | { readonly [key: string]: Json };
type GoldenCase = { readonly [key: string]: Json };

type GoldenSodium = {
  readonly sodium: Sodium;
  readonly draws: number[];
  readonly boxSeeds: Uint8Array[];
};

function goldenSodium(base: Sodium, seed: number): GoldenSodium {
  const counter = createCounterRandom(seed);
  const draws: number[] = [];
  const boxSeeds: Uint8Array[] = [];
  const randomBytes = (length: number): Uint8Array => {
    draws.push(length);
    return counter(length);
  };
  const boxKeypair = () => {
    const boxSeed = randomBytes(BOX_SEED_BYTES);
    boxSeeds.push(boxSeed.slice());
    const keypair = libsodium.crypto_box_seed_keypair(boxSeed);
    return { publicKey: keypair.publicKey, privateKey: keypair.privateKey };
  };
  return { sodium: { ...base, randomBytes, boxKeypair }, draws, boxSeeds };
}

function goldenKdfParams(salt: Uint8Array): KdfParams {
  return { salt, opsLimit: GOLDEN_OPS_LIMIT, memLimitBytes: GOLDEN_MEM_LIMIT_BYTES };
}

const GOLDEN_KDF = Object.freeze({ opsLimit: GOLDEN_OPS_LIMIT, memLimitBytes: GOLDEN_MEM_LIMIT_BYTES });

function hex(bytes: Uint8Array): string {
  return bytesToHex(bytes);
}

function sampleId(seed: number): string {
  return encodeBytes(counterBytes(seed, ID_BYTES));
}

function sampleBytes(seed: number, length: number): string {
  return encodeBytes(counterBytes(seed, length));
}

function requireEqual(left: Uint8Array, right: Uint8Array, what: string): void {
  if (!bytesEqual(left, right)) throw new Error(`golden self-check failed: ${what}`);
}

function requireFirst<T>(values: readonly T[], what: string): T {
  const first = values[0];
  if (first === undefined) throw new Error(`golden self-check failed: ${what}`);
  return first;
}

class GoldenWriter {
  readonly written: string[] = [];
  private readonly directory: string;

  constructor(directory: string) {
    this.directory = directory;
  }

  async bytes(relativePath: string, content: Uint8Array): Promise<void> {
    const target = join(this.directory, relativePath);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, content);
    this.written.push(relativePath);
  }

  async cases(relativePath: string, cases: readonly GoldenCase[]): Promise<void> {
    const document = { format: GOLDEN_FORMAT, generator: GENERATOR, cases };
    const text = `${JSON.stringify(document, null, JSON_INDENT)}\n`;
    await this.bytes(relativePath, new TextEncoder().encode(text));
  }
}

const TIMES = Object.freeze({
  taken: '2026-07-14T16:30:00.000Z',
  created: '2026-07-14T16:31:02.123Z',
  edited: '2026-07-15T08:00:00.000Z',
  captured: '2026-07-14T16:29:58.000Z',
  account: '2026-01-02T03:04:05.000Z',
  expires: '2026-02-01T00:00:00.000Z',
  later: '2026-03-15T12:00:00Z',
});

const SAMPLE_IDS = Object.freeze({
  user: sampleId(1),
  otherUser: sampleId(2),
  collection: sampleId(3),
  entry: sampleId(4),
  media: sampleId(5),
  otherMedia: sampleId(6),
  object: sampleId(7),
  thumbnailObject: sampleId(8),
  invite: sampleId(9),
  link: sampleId(10),
  audioMedia: sampleId(11),
});

const photoDescriptor = {
  mediaId: SAMPLE_IDS.media,
  kind: 'photo',
  mimeType: 'image/jpeg',
  width: 4032,
  height: 3024,
  durationMs: null,
  originalPlaintextBytes: 2_481_337,
  thumbnail: { width: 512, height: 384, mimeType: 'image/webp', plaintextBytes: 18_204 },
  capturedAtUtc: TIMES.captured,
  originalFileName: 'IMG_0042.jpg',
} as const;

const videoDescriptor = {
  mediaId: SAMPLE_IDS.otherMedia,
  kind: 'video',
  mimeType: 'video/mp4',
  width: 1920,
  height: 1080,
  durationMs: 12_480,
  originalPlaintextBytes: 31_457_280,
  thumbnail: { width: 512, height: 288, mimeType: 'image/webp', plaintextBytes: 9_876 },
  capturedAtUtc: null,
  originalFileName: null,
  caption: 'Waves at sunset',
} as const;

const audioDescriptor = {
  mediaId: SAMPLE_IDS.audioMedia,
  kind: 'audio',
  mimeType: 'audio/webm',
  width: 256,
  height: 256,
  durationMs: 4_321,
  originalPlaintextBytes: 51_234,
  thumbnail: { width: 256, height: 256, mimeType: 'image/webp', plaintextBytes: 1_024 },
  capturedAtUtc: null,
  originalFileName: null,
} as const;

const minimalEntry = {
  schemaVersion: 1,
  takenAtUtc: TIMES.taken,
  utcOffsetMinutes: 0,
  timeZone: null,
  note: '',
  tags: [],
  location: null,
  media: [photoDescriptor],
  createdAtUtc: TIMES.created,
  editedAtUtc: TIMES.created,
} as const;

const fullEntry = {
  schemaVersion: 1,
  takenAtUtc: TIMES.taken,
  utcOffsetMinutes: 120,
  timeZone: 'Europe/Amsterdam',
  note: '# Beach day\n\nSwam **twice**, then [ate](https://example.com/ice) ice cream.',
  tags: ['beach', 'summer', 'travel'],
  location: { latitude: 52.370216, longitude: 4.895168, accuracyMeters: 5, source: 'device' },
  media: [
    { ...photoDescriptor, caption: 'Sunset over the bay' },
    videoDescriptor,
    { ...audioDescriptor, caption: 'Seagulls' },
  ],
  createdAtUtc: TIMES.created,
  editedAtUtc: TIMES.edited,
  favorite: true,
} as const;

const longNoteEntry = {
  ...minimalEntry,
  note: LONG_NOTE_UNIT.repeat(Math.ceil(NOTE_MAX_CHARS / LONG_NOTE_UNIT.length)).slice(0, NOTE_MAX_CHARS),
  tags: ['long'],
};

const nonAsciiEntry = {
  schemaVersion: 1,
  takenAtUtc: TIMES.taken,
  utcOffsetMinutes: 540,
  timeZone: 'Asia/Tokyo',
  note: 'Café — 東京 🌸 naïve e\u0301t\u00e9 Ελληνικά עברית\n"quoted" \\ back\tslash \u0001 / \u2028 end',
  tags: ['日本', 'café', '🌊', 'Ünïcødé'],
  location: { latitude: -33.8688, longitude: 151.2093, accuracyMeters: 12.5, source: 'exif' },
  media: [{ ...photoDescriptor, originalFileName: 'фото 📷.jpg', caption: 'Ünïcødé 🌸 «»' }],
  createdAtUtc: TIMES.created,
  editedAtUtc: TIMES.edited,
  favorite: false,
} as const;

const homeCollection = { schemaVersion: 1, name: 'Journal', createdAtUtc: TIMES.account } as const;
const nonAsciiCollection = {
  schemaVersion: 1,
  name: 'Été 2026 — 夏休み 🌞',
  createdAtUtc: TIMES.created,
} as const;

type SealedSpec = {
  readonly name: string;
  readonly schema: 'EntryMetadataV1' | 'CollectionMetadataV1';
  readonly value: unknown;
  readonly bindingId: string;
  readonly keySeed: number;
  readonly randomSeed: number;
};

const SEALED_SPECS: readonly SealedSpec[] = [
  { name: 'entry-minimal', schema: 'EntryMetadataV1', value: minimalEntry, bindingId: sampleId(101), keySeed: 201, randomSeed: 301 },
  { name: 'entry-full', schema: 'EntryMetadataV1', value: fullEntry, bindingId: sampleId(102), keySeed: 202, randomSeed: 302 },
  { name: 'entry-long-note', schema: 'EntryMetadataV1', value: longNoteEntry, bindingId: sampleId(103), keySeed: 203, randomSeed: 303 },
  { name: 'entry-non-ascii', schema: 'EntryMetadataV1', value: nonAsciiEntry, bindingId: sampleId(104), keySeed: 204, randomSeed: 304 },
  { name: 'collection-home', schema: 'CollectionMetadataV1', value: homeCollection, bindingId: sampleId(105), keySeed: 205, randomSeed: 305 },
  { name: 'collection-non-ascii', schema: 'CollectionMetadataV1', value: nonAsciiCollection, bindingId: sampleId(106), keySeed: 206, randomSeed: 306 },
];

function sealedJsonCases(base: Sodium): GoldenCase[] {
  return SEALED_SPECS.map((spec) => {
    const value =
      spec.schema === 'EntryMetadataV1' ? EntryMetadataV1.parse(spec.value) : CollectionMetadataV1.parse(spec.value);
    const context: AdContext =
      spec.schema === 'EntryMetadataV1' ? AD_CONTEXTS.entryMetadata : AD_CONTEXTS.collectionMetadata;
    const key = counterBytes(spec.keySeed, KEY_BYTES);
    const { sodium } = goldenSodium(base, spec.randomSeed);
    const blob = sealJson(sodium, key, value, context, spec.bindingId);
    return {
      name: spec.name,
      schema: spec.schema,
      context,
      bindingId: spec.bindingId,
      key: hex(key),
      randomSeed: spec.randomSeed,
      json: JSON.stringify(value),
      blob: hex(blob),
    };
  });
}

type StreamSpec = {
  readonly name: string;
  readonly plaintextSize: number;
  readonly plaintextSeed: number;
  readonly keySeed: number;
  readonly randomSeed: number;
};

const STREAM_SPECS: readonly StreamSpec[] = [
  { name: 'empty', plaintextSize: 0, plaintextSeed: 401, keySeed: 411, randomSeed: 421 },
  { name: 'five-bytes', plaintextSize: 5, plaintextSeed: 402, keySeed: 412, randomSeed: 422 },
  { name: 'one-chunk-exact', plaintextSize: 1_048_576, plaintextSeed: 403, keySeed: 413, randomSeed: 423 },
  { name: 'one-chunk-plus-one', plaintextSize: 1_048_577, plaintextSeed: 404, keySeed: 414, randomSeed: 424 },
];

async function streamObjectCases(base: Sodium, writer: GoldenWriter): Promise<GoldenCase[]> {
  const cases: GoldenCase[] = [];
  for (const spec of STREAM_SPECS) {
    const key = counterBytes(spec.keySeed, KEY_BYTES);
    const plaintext = counterBytes(spec.plaintextSeed, spec.plaintextSize);
    const { sodium } = goldenSodium(base, spec.randomSeed);
    const encryptor = createStreamEncryptor(sodium, key, spec.plaintextSize);
    const { chunkSize, chunkCount, ciphertextSize } = encryptor.layout;
    const chunks: Uint8Array[] = [];
    for (let index = 0; index < chunkCount; index += 1) {
      const start = index * chunkSize;
      chunks.push(encryptor.encryptNext(plaintext.slice(start, start + chunkSize)));
    }
    const object = concatBytes(encryptor.header, ...chunks);
    const file = `${STREAM_FILE_DIRECTORY}/${spec.name}${STREAM_FILE_EXTENSION}`;
    await writer.bytes(file, object);
    cases.push({
      name: spec.name,
      key: hex(key),
      plaintextSeed: spec.plaintextSeed,
      plaintextSize: spec.plaintextSize,
      randomSeed: spec.randomSeed,
      file,
      objectSha256: createHash('sha256').update(object).digest('hex'),
      header: hex(encryptor.header),
      chunkCount,
      ciphertextSize,
    });
  }
  return cases;
}

type WireExtras = { readonly inviteToken: string | null; readonly deviceLabel: string };

function signUpWire(request: SignUpRequestBytes, extras: WireExtras) {
  return signUpRequest.parse({
    userId: request.userId,
    username: request.username,
    inviteToken: extras.inviteToken,
    deviceLabel: extras.deviceLabel,
    salt: encodeBytes(request.salt),
    opsLimit: request.opsLimit,
    memLimitBytes: request.memLimitBytes,
    loginKey: encodeBytes(request.loginKey),
    recoveryLoginKey: encodeBytes(request.recoveryLoginKey),
    publicKey: encodeBytes(request.publicKey),
    masterKeyByKek: encodeBytes(request.masterKeyByKek),
    masterKeyByRecovery: encodeBytes(request.masterKeyByRecovery),
    recoveryKeyByMaster: encodeBytes(request.recoveryKeyByMaster),
    privateKeyByMaster: encodeBytes(request.privateKeyByMaster),
    homeCollection: {
      id: request.homeCollection.id,
      keyByOwner: encodeBytes(request.homeCollection.keyByOwner),
      metadataBlob: encodeBytes(request.homeCollection.metadataBlob),
    },
  });
}

function recoveryWire(username: string, completion: RecoveryCompleteBytes, deviceLabel: string) {
  return recoveryCompleteRequest.parse({
    username,
    recoveryLoginKey: encodeBytes(completion.recoveryLoginKey),
    deviceLabel,
    salt: encodeBytes(completion.salt),
    opsLimit: completion.opsLimit,
    memLimitBytes: completion.memLimitBytes,
    loginKey: encodeBytes(completion.loginKey),
    masterKeyByKek: encodeBytes(completion.masterKeyByKek),
  });
}

function passwordChangeWire(material: PasswordChangeBytes) {
  return passwordChangeRequest.parse({
    currentLoginKey: encodeBytes(material.currentLoginKey),
    salt: encodeBytes(material.salt),
    opsLimit: material.opsLimit,
    memLimitBytes: material.memLimitBytes,
    loginKey: encodeBytes(material.loginKey),
    masterKeyByKek: encodeBytes(material.masterKeyByKek),
  });
}

function storedFromSignUp(request: SignUpRequestBytes): StoredLocalAccount {
  return {
    userId: request.userId,
    kdfParams: { salt: request.salt, opsLimit: request.opsLimit, memLimitBytes: request.memLimitBytes },
    masterKeyByKek: request.masterKeyByKek,
    masterKeyByRecovery: request.masterKeyByRecovery,
    recoveryKeyByMaster: request.recoveryKeyByMaster,
    publicKey: request.publicKey,
    privateKeyByMaster: request.privateKeyByMaster,
    homeCollection: request.homeCollection,
  };
}

function storedToJson(stored: StoredLocalAccount): GoldenCase {
  return {
    userId: stored.userId,
    kdfParams: {
      salt: hex(stored.kdfParams.salt),
      opsLimit: stored.kdfParams.opsLimit,
      memLimitBytes: stored.kdfParams.memLimitBytes,
    },
    masterKeyByKek: hex(stored.masterKeyByKek),
    masterKeyByRecovery: hex(stored.masterKeyByRecovery),
    recoveryKeyByMaster: hex(stored.recoveryKeyByMaster),
    publicKey: hex(stored.publicKey),
    privateKeyByMaster: hex(stored.privateKeyByMaster),
    homeCollection: {
      id: stored.homeCollection.id,
      keyByOwner: hex(stored.homeCollection.keyByOwner),
      metadataBlob: hex(stored.homeCollection.metadataBlob),
    },
  };
}

type SignUpSpec = {
  readonly name: string;
  readonly username: string;
  readonly password: string;
  readonly homeCollectionName: string;
  readonly createdAtUtc: string;
  readonly randomSeed: number;
  readonly inviteToken: string | null;
  readonly deviceLabel: string;
};

type SignUpResult = {
  readonly spec: SignUpSpec;
  readonly request: SignUpRequestBytes;
  readonly recoveryWords: readonly string[];
  readonly goldenCase: GoldenCase;
};

const SIGN_UP_SPECS: readonly SignUpSpec[] = [
  {
    name: 'ascii-password-open-signup',
    username: 'alice',
    password: 'correct horse battery staple',
    homeCollectionName: 'Journal',
    createdAtUtc: TIMES.account,
    randomSeed: 501,
    inviteToken: null,
    deviceLabel: 'Pixel 8',
  },
  {
    name: 'non-ascii-password-with-invite',
    username: 'juergen.k',
    password: 'Pässwörd e\u0301té 東京 🌸 ﬁ２',
    homeCollectionName: 'Journal',
    createdAtUtc: TIMES.created,
    randomSeed: 502,
    inviteToken: `${INVITE_TOKEN_PREFIX}${sampleBytes(503, SAMPLE_TOKEN_BYTES)}`,
    deviceLabel: 'Galaxy Tab S9 — Küche',
  },
];

async function signUpCase(base: Sodium, spec: SignUpSpec): Promise<SignUpResult> {
  const { sodium, draws, boxSeeds } = goldenSodium(base, spec.randomSeed);
  const material = await createSignUpMaterial(
    sodium,
    {
      username: spec.username,
      password: spec.password,
      homeCollectionName: spec.homeCollectionName,
      createdAtUtc: spec.createdAtUtc,
      randomId: () => encodeBytes(sodium.randomBytes(ID_BYTES)),
    },
    goldenKdfParams,
  );
  const { request, secrets, recoveryWords } = material;
  const login = await deriveLoginMaterial(base, spec.password, {
    salt: request.salt,
    opsLimit: request.opsLimit,
    memLimitBytes: request.memLimitBytes,
  });
  requireEqual(login.loginKey, request.loginKey, `${spec.name} loginKey`);
  const recoveryKey = decodeRecoveryWords(base, recoveryWords);
  const metadataJson = JSON.stringify(
    CollectionMetadataV1.parse({ schemaVersion: 1, name: spec.homeCollectionName, createdAtUtc: spec.createdAtUtc }),
  );
  const goldenCase: GoldenCase = {
    name: spec.name,
    inputs: {
      username: spec.username,
      password: spec.password,
      homeCollectionName: spec.homeCollectionName,
      createdAtUtc: spec.createdAtUtc,
      randomSeed: spec.randomSeed,
      randomIdBytes: ID_BYTES,
      kdf: GOLDEN_KDF,
      inviteToken: spec.inviteToken,
      deviceLabel: spec.deviceLabel,
    },
    outputs: {
      randomDraws: draws,
      passwordBytes: hex(passwordToBytes(spec.password)),
      salt: hex(request.salt),
      kek: hex(login.kek),
      loginKey: hex(request.loginKey),
      masterKey: hex(secrets.masterKey),
      recoveryKey: hex(recoveryKey),
      recoveryLoginKey: hex(request.recoveryLoginKey),
      recoveryWords: [...recoveryWords],
      homeCollectionKey: hex(secrets.homeCollectionKey),
      boxSeed: hex(requireFirst(boxSeeds, `${spec.name} box seed`)),
      publicKey: hex(secrets.publicKey),
      privateKey: hex(secrets.privateKey),
      userId: secrets.userId,
      homeCollectionId: secrets.homeCollectionId,
      homeCollectionMetadataJson: metadataJson,
      request: signUpWire(request, spec),
    },
  };
  return { spec, request, recoveryWords, goldenCase };
}

type RecoverySpec = {
  readonly name: string;
  readonly wordsInput: (words: readonly string[]) => string;
  readonly newPassword: string;
  readonly randomSeed: number;
  readonly deviceLabel: string;
};

const FULLWIDTH_OFFSET = 0xfee0;

function fullwidth(text: string): string {
  return [...text].map((character) => String.fromCodePoint((character.codePointAt(0) ?? 0) + FULLWIDTH_OFFSET)).join('');
}

const RECOVERY_SPECS: readonly RecoverySpec[] = [
  {
    name: 'plain-words',
    wordsInput: (words) => words.join(' '),
    newPassword: 'a brand new passphrase 2026',
    randomSeed: 601,
    deviceLabel: 'Pixel 8',
  },
  {
    name: 'messy-words-non-ascii-password',
    wordsInput: (words) =>
      `  ${words
        .map((word, index) => (index % 3 === 0 ? word.toUpperCase() : index % 3 === 1 ? fullwidth(word) : word))
        .join(' \t\n ')}\n`,
    newPassword: 'Neues Pässwört 🌸 東京 e\u0301',
    randomSeed: 602,
    deviceLabel: 'Pixel 8',
  },
];

async function recoveryCase(base: Sodium, account: SignUpResult, spec: RecoverySpec): Promise<GoldenCase> {
  const wordsInput = spec.wordsInput(account.recoveryWords);
  const words = normalizeRecoveryInput(wordsInput);
  const { sodium, draws } = goldenSodium(base, spec.randomSeed);
  const opened = openRecovery(sodium, words, account.request.userId, account.request.masterKeyByRecovery);
  const completion = await createRecoveryCompletion(
    sodium,
    {
      userId: account.request.userId,
      newPassword: spec.newPassword,
      masterKey: opened.masterKey,
      recoveryKey: opened.recoveryKey,
    },
    goldenKdfParams,
  );
  const login = await deriveLoginMaterial(base, spec.newPassword, {
    salt: completion.salt,
    opsLimit: completion.opsLimit,
    memLimitBytes: completion.memLimitBytes,
  });
  requireEqual(login.loginKey, completion.loginKey, `${spec.name} loginKey`);
  return {
    name: spec.name,
    inputs: {
      username: account.spec.username,
      userId: account.request.userId,
      masterKeyByRecovery: hex(account.request.masterKeyByRecovery),
      wordsInput,
      newPassword: spec.newPassword,
      randomSeed: spec.randomSeed,
      kdf: GOLDEN_KDF,
      deviceLabel: spec.deviceLabel,
    },
    outputs: {
      normalizedWords: [...words],
      recoveryKey: hex(opened.recoveryKey),
      masterKey: hex(opened.masterKey),
      randomDraws: draws,
      passwordBytes: hex(passwordToBytes(spec.newPassword)),
      salt: hex(completion.salt),
      kek: hex(login.kek),
      loginKey: hex(completion.loginKey),
      recoveryLoginKey: hex(completion.recoveryLoginKey),
      masterKeyByKek: hex(completion.masterKeyByKek),
      request: recoveryWire(account.spec.username, completion, spec.deviceLabel),
    },
  };
}

type PasswordChangeResult = { readonly goldenCase: GoldenCase; readonly stored: StoredLocalAccount; readonly password: string };

const PASSWORD_CHANGE_SPEC = Object.freeze({
  name: 'ascii-to-non-ascii-password',
  newPassword: 'Ünïcødé passphrase 🌊 ２０２６',
  randomSeed: 701,
});

async function passwordChangeCase(base: Sodium, account: SignUpResult): Promise<PasswordChangeResult> {
  const spec = PASSWORD_CHANGE_SPEC;
  const currentParams: KdfParams = {
    salt: account.request.salt,
    opsLimit: account.request.opsLimit,
    memLimitBytes: account.request.memLimitBytes,
  };
  const { sodium, draws } = goldenSodium(base, spec.randomSeed);
  const material = await createPasswordChangeMaterial(
    sodium,
    {
      userId: account.request.userId,
      currentPassword: account.spec.password,
      newPassword: spec.newPassword,
      currentParams,
      cachedMasterKeyByKek: account.request.masterKeyByKek,
    },
    goldenKdfParams,
  );
  const current = await deriveLoginMaterial(base, account.spec.password, currentParams);
  const next = await deriveLoginMaterial(base, spec.newPassword, {
    salt: material.salt,
    opsLimit: material.opsLimit,
    memLimitBytes: material.memLimitBytes,
  });
  requireEqual(next.loginKey, material.loginKey, `${spec.name} loginKey`);
  const masterKey = unwrapKey(base, current.kek, account.request.masterKeyByKek, AD_CONTEXTS.masterKeyByKek, account.request.userId);
  const goldenCase: GoldenCase = {
    name: spec.name,
    inputs: {
      userId: account.request.userId,
      currentPassword: account.spec.password,
      newPassword: spec.newPassword,
      currentKdf: { salt: hex(currentParams.salt), opsLimit: currentParams.opsLimit, memLimitBytes: currentParams.memLimitBytes },
      cachedMasterKeyByKek: hex(account.request.masterKeyByKek),
      randomSeed: spec.randomSeed,
      kdf: GOLDEN_KDF,
    },
    outputs: {
      randomDraws: draws,
      currentKek: hex(current.kek),
      currentLoginKey: hex(material.currentLoginKey),
      masterKey: hex(masterKey),
      passwordBytes: hex(passwordToBytes(spec.newPassword)),
      salt: hex(material.salt),
      kek: hex(next.kek),
      loginKey: hex(material.loginKey),
      masterKeyByKek: hex(material.masterKeyByKek),
      request: passwordChangeWire(material),
    },
  };
  const stored: StoredLocalAccount = {
    ...storedFromSignUp(account.request),
    kdfParams: { salt: material.salt, opsLimit: material.opsLimit, memLimitBytes: material.memLimitBytes },
    masterKeyByKek: material.masterKeyByKek,
  };
  return { goldenCase, stored, password: spec.newPassword };
}

type LocalSpec = {
  readonly name: string;
  readonly stored: StoredLocalAccount;
  readonly password: string;
  readonly username: string;
  readonly extras: WireExtras;
};

async function localAccountCase(base: Sodium, spec: LocalSpec): Promise<GoldenCase> {
  const request = await rebuildSignUpRequest(base, {
    password: spec.password,
    username: spec.username,
    stored: spec.stored,
  });
  const login = await deriveLoginMaterial(base, spec.password, spec.stored.kdfParams);
  requireEqual(login.loginKey, request.loginKey, `${spec.name} loginKey`);
  const masterKey = unwrapKey(base, login.kek, spec.stored.masterKeyByKek, AD_CONTEXTS.masterKeyByKek, spec.stored.userId);
  const recoveryKey = unwrapKey(base, masterKey, spec.stored.recoveryKeyByMaster, AD_CONTEXTS.recoveryKeyByMaster, spec.stored.userId);
  return {
    name: spec.name,
    inputs: {
      password: spec.password,
      username: spec.username,
      stored: storedToJson(spec.stored),
      inviteToken: spec.extras.inviteToken,
      deviceLabel: spec.extras.deviceLabel,
    },
    outputs: {
      passwordBytes: hex(passwordToBytes(spec.password)),
      kek: hex(login.kek),
      loginKey: hex(request.loginKey),
      masterKey: hex(masterKey),
      recoveryKey: hex(recoveryKey),
      recoveryLoginKey: hex(request.recoveryLoginKey),
      request: signUpWire(request, spec.extras),
    },
  };
}

type Samples = { readonly valid: readonly Json[]; readonly invalid: readonly Json[] };

function dtoSamples(requests: {
  readonly signUp: readonly Json[];
  readonly recovery: readonly Json[];
  readonly passwordChange: readonly Json[];
}): Record<string, Samples> {
  const id = SAMPLE_IDS;
  const key = sampleBytes(21, KEY_BYTES);
  const wrapped = sampleBytes(22, SAMPLE_WRAPPED_KEY_BYTES);
  const blob = sampleBytes(23, SAMPLE_BLOB_BYTES);
  const salt = sampleBytes(24, ID_BYTES);
  const sealedBox = sampleBytes(25, SAMPLE_SEALED_BOX_BYTES);
  const sessionToken = `${SESSION_TOKEN_PREFIX}${sampleBytes(26, SAMPLE_TOKEN_BYTES)}`;
  const inviteToken = `${INVITE_TOKEN_PREFIX}${sampleBytes(27, SAMPLE_TOKEN_BYTES)}`;
  const url = 'https://s3.example.com/kodakiko/objects/abc?X-Amz-Signature=deadbeef&X-Amz-Expires=900';
  const t = TIMES;

  const session = { token: sessionToken, expiresAt: t.expires, deviceLabel: 'Pixel 8' };
  const user = {
    id: id.user,
    username: 'alice',
    role: 'member',
    status: 'active',
    quotaBytes: 10_737_418_240,
    usedBytes: 52_428_800,
    createdAt: t.account,
    deletionDueAt: null,
  };
  const pendingUser = { ...user, id: id.otherUser, username: 'bob', role: 'admin', status: 'pending_deletion', quotaBytes: null, deletionDueAt: t.later };
  const adminUser = { ...user, sessionCount: 2, lastActiveAt: t.edited };
  const keyBundle = {
    salt,
    opsLimit: 16,
    memLimitBytes: 268_435_456,
    masterKeyByKek: wrapped,
    recoveryKeyByMaster: wrapped,
    publicKey: key,
    privateKeyByMaster: wrapped,
  };
  const entry = {
    id: id.entry,
    ownerId: id.user,
    purpose: 'journal',
    revision: 3,
    metadataBlob: blob,
    trashedAt: null,
    createdAt: t.created,
    updatedAt: t.edited,
  };
  const trashedLinkEntry = { ...entry, purpose: 'link_copy', revision: 1, trashedAt: t.later };
  const media = { id: id.media, entryId: id.entry, mediaKeyByEntry: wrapped, createdAt: t.created };
  const object = { id: id.object, mediaId: id.media, variant: 'original', sizeBytes: 2_481_401, state: 'uploaded' };
  const thumbnailObject = { id: id.thumbnailObject, mediaId: id.media, variant: 'thumbnail', sizeBytes: 18_244, state: 'pending' };
  const collection = {
    id: id.collection,
    kind: 'home',
    role: 'owner',
    ownerId: id.user,
    ownerUsername: 'alice',
    keyMaterial: wrapped,
    metadataBlob: blob,
    lastSeq: 42,
    createdAt: t.account,
    metaUpdatedAt: t.edited,
    recipientCount: null,
  };
  const sharedCollection = { ...collection, kind: 'shared', role: 'viewer', ownerId: id.otherUser, ownerUsername: 'bob', keyMaterial: sealedBox };
  const { recipientCount: _omitted, ...collectionWithoutRecipientCount } = sharedCollection;
  const invite = {
    id: id.invite,
    role: 'member',
    createdAt: t.account,
    expiresAt: t.expires,
    redeemedAt: null,
    revokedAt: null,
    redeemedByUsername: null,
  };
  const share = { recipientUserId: id.otherUser, recipientUsername: 'bob', createdAt: t.edited };
  const publicLink = { linkId: id.link, collectionId: id.collection, expiresAt: t.expires, maxViews: null, viewCount: 0, createdAt: t.created };
  const linkObject = { ...object, download: { kind: 'presigned', url, expiresAt: t.expires } };
  const linkProxyObject = { ...thumbnailObject, state: 'uploaded', download: { kind: 'proxy', path: `/api/v1/public-links/${id.link}/objects/${id.thumbnailObject}/content` } };
  const linkEntry = { entry, entryKeyByCollection: wrapped, media: [media], objects: [linkObject, linkProxyObject] };
  const membership = { entryId: id.entry, entryKeyByCollection: wrapped, seq: 41, removed: false };
  const removedMembership = { entryId: id.media, entryKeyByCollection: null, seq: 42, removed: true };
  const diffPage = {
    collectionId: id.collection,
    memberships: [membership, removedMembership],
    entries: [entry],
    media: [media],
    objects: [object, thumbnailObject],
    nextCursor: 42,
    hasMore: false,
    resyncRequired: false,
  };
  const config = {
    apiVersion: 1,
    signupMode: 'invite',
    sharingEnabled: true,
    publicLinksEnabled: true,
    maxMediaPerEntry: 20,
    maxUploadBytes: 4_294_967_296,
    defaultQuotaBytes: 10_737_418_240,
    trashDays: 30,
    accountDeletionGraceDays: 14,
    serverVersion: '0.4.0',
  };
  const tooLong = (length: number) => 'x'.repeat(length);
  const badId = 'not-an-id';
  const firstSignUp = requireFirst(requests.signUp, 'sign-up sample');
  const signUpObject = typeof firstSignUp === 'object' && firstSignUp !== null && !Array.isArray(firstSignUp) ? firstSignUp : {};

  return {
    CollectionMetadataV1: {
      valid: [homeCollection, nonAsciiCollection],
      invalid: [
        { ...homeCollection, schemaVersion: 2 },
        { ...homeCollection, name: '' },
        { ...homeCollection, name: tooLong(101) },
        { schemaVersion: 1, name: 'Journal' },
        { ...homeCollection, createdAtUtc: '2026-01-02T03:04:05+01:00' },
      ],
    },
    EntryMetadataV1: {
      valid: [minimalEntry, fullEntry, nonAsciiEntry],
      invalid: [
        { ...minimalEntry, media: [] },
        { ...minimalEntry, utcOffsetMinutes: 841 },
        { ...minimalEntry, tags: [''] },
        { ...minimalEntry, location: { latitude: 91, longitude: 0, accuracyMeters: null, source: 'device' } },
        { ...minimalEntry, takenAtUtc: '2026-07-14 16:30:00' },
      ],
    },
    MediaDescriptorV1: {
      valid: [photoDescriptor, videoDescriptor, audioDescriptor],
      invalid: [
        { ...photoDescriptor, kind: 'document' },
        { ...photoDescriptor, width: 0 },
        { ...photoDescriptor, caption: '' },
        { ...photoDescriptor, caption: tooLong(65) },
        { ...photoDescriptor, thumbnail: { ...photoDescriptor.thumbnail, mimeType: 'image/jpeg' } },
      ],
    },
    TagName: { valid: ['beach', '日本', tooLong(64)], invalid: ['', tooLong(65), 5] },
    adminIdParams: { valid: [{ id: id.user }], invalid: [{ id: badId }, {}] },
    adminInviteCreateRequest: {
      valid: [{ role: 'member', expiresInDays: 7 }, { role: 'admin', expiresInDays: 90 }],
      invalid: [{ role: 'member', expiresInDays: 0 }, { role: 'member', expiresInDays: 91 }, { role: 'owner', expiresInDays: 7 }, { role: 'member', expiresInDays: 1.5 }],
    },
    adminInviteCreateResponse: {
      valid: [{ inviteUrl: `https://kodakiko.example.com/invite#${inviteToken}`, expiresAt: t.expires }],
      invalid: [{ inviteUrl: 'https://kodakiko.example.com/invite', expiresAt: 'tomorrow' }, { expiresAt: t.expires }],
    },
    adminStatsResponse: {
      valid: [
        { users: 3, activeUsers30d: 2, pendingDeletion: 0, collections: 5, entries: 120, objects: 480, totalBytes: 9_876_543_210, quotaBytesAllocated: null },
        { users: 0, activeUsers30d: 0, pendingDeletion: 0, collections: 0, entries: 0, objects: 0, totalBytes: 0, quotaBytesAllocated: 0 },
      ],
      invalid: [
        { users: -1, activeUsers30d: 0, pendingDeletion: 0, collections: 0, entries: 0, objects: 0, totalBytes: 0, quotaBytesAllocated: null },
        { users: 1.5, activeUsers30d: 0, pendingDeletion: 0, collections: 0, entries: 0, objects: 0, totalBytes: 0, quotaBytesAllocated: null },
      ],
    },
    adminUserDto: {
      valid: [adminUser, { ...pendingUser, sessionCount: 0, lastActiveAt: null }],
      invalid: [user, { ...adminUser, sessionCount: -1 }],
    },
    adminUserListQuery: {
      valid: [{}, { cursor: 'abc', limit: '20' }, { limit: 5 }],
      invalid: [{ limit: '0' }, { cursor: '' }, { limit: 'many' }],
    },
    adminUserListResponse: {
      valid: [{ items: [adminUser], nextCursor: null }, { items: [], nextCursor: 'cursor-2' }],
      invalid: [{ items: [adminUser] }, { items: [user], nextCursor: null }],
    },
    adminUserPatchRequest: {
      valid: [{ quotaBytes: 1_073_741_824 }, { quotaBytes: null }, { role: 'admin' }, { quotaBytes: 0, role: 'member' }],
      invalid: [{}, { quotaBytes: -1 }, { role: 'owner' }],
    },
    bytesSchema: { valid: ['AQID', key, 'AA'], invalid: ['', 'A', 'AQ==', 'a+b/', 'AAAAA'] },
    collectionCreateRequest: {
      valid: [
        { id: id.collection, kind: 'shared', keyByOwner: wrapped, metadataBlob: blob },
        { id: id.collection, kind: 'link', keyByOwner: wrapped, metadataBlob: blob },
      ],
      invalid: [{ id: id.collection, kind: 'home', keyByOwner: wrapped, metadataBlob: blob }, { id: badId, kind: 'shared', keyByOwner: wrapped, metadataBlob: blob }],
    },
    collectionDto: {
      valid: [collection, collectionWithoutRecipientCount, { ...collection, kind: 'link', recipientCount: 3 }],
      invalid: [{ ...collection, lastSeq: -1 }, { ...collection, kind: 'album' }, { ...collection, role: 'editor' }],
    },
    collectionEntryAddRequest: {
      valid: [{ entryId: id.entry, entryKeyByCollection: wrapped }],
      invalid: [{ entryId: id.entry }, { entryId: id.entry, entryKeyByCollection: 'A' }],
    },
    collectionEntryParams: { valid: [{ id: id.collection, entryId: id.entry }], invalid: [{ id: id.collection }, { id: id.collection, entryId: badId }] },
    collectionIdParams: { valid: [{ id: id.collection }], invalid: [{ id: badId }, {}] },
    collectionListQuery: {
      valid: [{}, { cursor: 'abc', limit: '50' }],
      invalid: [{ limit: '0' }, { cursor: '' }],
    },
    collectionListResponse: {
      valid: [{ items: [collection, sharedCollection], nextCursor: null }, { items: [], nextCursor: 'next' }],
      invalid: [{ items: [collection] }, { items: [{ ...collection, id: badId }], nextCursor: null }],
    },
    collectionPatchRequest: { valid: [{ metadataBlob: blob }], invalid: [{ metadataBlob: '' }, {}] },
    deletionRequestResponse: { valid: [{ deletionDueAt: t.later }], invalid: [{ deletionDueAt: '2026-03-15' }, {}] },
    diffPage: {
      valid: [diffPage, { ...diffPage, memberships: [], entries: [], media: [], objects: [], nextCursor: 0, hasMore: true, resyncRequired: true }],
      invalid: [{ ...diffPage, nextCursor: -1 }, { ...diffPage, memberships: [{ entryId: id.entry, seq: 1, removed: false }] }],
    },
    diffQuery: {
      valid: [{ since: '0' }, { since: '42', limit: '100' }, { since: 5 }],
      invalid: [{}, { since: '-1' }, { since: '1', limit: '0' }],
    },
    downloadResponse: { valid: [{ url, expiresAt: t.expires }], invalid: [{ url: 'not a url', expiresAt: t.expires }, { url }] },
    entryCreateRequest: {
      valid: [
        {
          id: id.entry,
          purpose: 'journal',
          metadataBlob: blob,
          memberships: [{ collectionId: id.collection, entryKeyByCollection: wrapped }],
          media: [
            { mediaId: id.media, mediaKeyByEntry: wrapped },
            { mediaId: id.otherMedia, mediaKeyByEntry: wrapped },
          ],
        },
      ],
      invalid: [
        { id: id.entry, purpose: 'journal', metadataBlob: blob, memberships: [], media: [{ mediaId: id.media, mediaKeyByEntry: wrapped }] },
        { id: id.entry, purpose: 'journal', metadataBlob: blob, memberships: [{ collectionId: id.collection, entryKeyByCollection: wrapped }], media: [] },
        { id: id.entry, purpose: 'diary', metadataBlob: blob, memberships: [{ collectionId: id.collection, entryKeyByCollection: wrapped }], media: [{ mediaId: id.media, mediaKeyByEntry: wrapped }] },
      ],
    },
    entryDto: { valid: [entry, trashedLinkEntry], invalid: [{ ...entry, revision: 0 }, { ...entry, purpose: 'diary' }] },
    entryIdParams: { valid: [{ id: id.entry }], invalid: [{ id: badId }] },
    entryPatchRequest: { valid: [{ metadataBlob: blob, baseRevision: 3 }], invalid: [{ metadataBlob: blob, baseRevision: 0 }, { metadataBlob: blob }] },
    errorResponseSchema: {
      valid: [
        { error: { code: 'NOT_FOUND', message: 'Not found', requestId: 'req-1', details: null } },
        { error: { code: 'REVISION_CONFLICT', message: 'Revision conflict', requestId: 'req-2', details: { revision: 4, metadataBlob: blob } } },
      ],
      invalid: [{ error: { code: 'TEAPOT', message: 'x', requestId: 'req-3', details: null } }, { code: 'NOT_FOUND' }],
    },
    idSchema: { valid: [id.user, 'AAAAAAAAAAAAAAAAAAAAAA', 'zzzzzzzzzzzzzzzzzzzzz-'], invalid: ['short', 'AAAAAAAAAAAAAAAAAAAAA', 'AAAAAAAAAAAAAAAAAAAAAAA', 'AAAAAAAAAAAAAAAAAAAAA+', 22] },
    inviteDto: {
      valid: [invite, { ...invite, role: 'admin', redeemedAt: t.edited, redeemedByUsername: 'carol' }, { ...invite, revokedAt: t.edited }],
      invalid: [{ ...invite, role: 'owner' }, { ...invite, expiresAt: null }],
    },
    inviteListResponse: { valid: [{ items: [invite] }, { items: [] }], invalid: [{}, { items: [{ ...invite, id: badId }] }] },
    keyBundleDto: {
      valid: [keyBundle],
      invalid: [{ ...keyBundle, opsLimit: 0 }, { ...keyBundle, opsLimit: 2_147_483_648 }, { ...keyBundle, salt: '' }],
    },
    linkDownload: {
      valid: [{ kind: 'presigned', url, expiresAt: t.expires }, { kind: 'proxy', path: '/api/v1/public-links/x/objects/y/content' }],
      invalid: [{ kind: 'proxy', path: 'relative/path' }, { kind: 'stream', url }, { kind: 'presigned', url: 'nope', expiresAt: t.expires }],
    },
    linkEntriesQuery: { valid: [{}, { cursor: 'abc', limit: '10' }], invalid: [{ limit: '0' }, { cursor: '' }] },
    linkEntriesResponse: {
      valid: [{ items: [linkEntry], nextCursor: null }, { items: [], nextCursor: 'next' }],
      invalid: [{ items: [linkEntry] }, { items: [{ ...linkEntry, objects: [object] }], nextCursor: null }],
    },
    linkEntryItem: { valid: [linkEntry, { ...linkEntry, media: [], objects: [] }], invalid: [{ ...linkEntry, entryKeyByCollection: '' }, { entry, media: [], objects: [] }] },
    linkExpirySchema: { valid: ['1h', '1d', '7d', '14d', '30d', 'never', 'one_view'], invalid: ['2d', '', 'NEVER'] },
    linkObjectDto: { valid: [linkObject, linkProxyObject], invalid: [object, { ...linkObject, sizeBytes: 39 }] },
    linkOpenResponse: {
      valid: [
        { collectionId: id.collection, metadataBlob: blob, expiresAt: t.expires, maxViews: null, viewToken: 'view-token-1' },
        { collectionId: id.collection, metadataBlob: blob, expiresAt: null, maxViews: 1, viewToken: 'v' },
      ],
      invalid: [
        { collectionId: id.collection, metadataBlob: blob, expiresAt: null, maxViews: null, viewToken: '' },
        { collectionId: id.collection, metadataBlob: blob, expiresAt: null, maxViews: 0, viewToken: 'v' },
      ],
    },
    mediaCreateRequest: { valid: [{ mediaId: id.media, mediaKeyByEntry: wrapped }], invalid: [{ mediaId: badId, mediaKeyByEntry: wrapped }, { mediaId: id.media }] },
    mediaDto: { valid: [media], invalid: [{ ...media, createdAt: 'yesterday' }, { ...media, entryId: badId }] },
    mediaIdParams: { valid: [{ id: id.media }], invalid: [{ id: badId }] },
    objectDto: {
      valid: [object, thumbnailObject, { ...object, sizeBytes: 40, state: 'deleting' }],
      invalid: [{ ...object, sizeBytes: 39 }, { ...object, state: 'gone' }, { ...object, variant: 'preview' }],
    },
    objectIdParams: { valid: [{ id: id.object }], invalid: [{ id: badId }] },
    objectStateSchema: { valid: ['pending', 'uploaded', 'deleting'], invalid: ['deleted', ''] },
    objectVariantSchema: { valid: ['original', 'thumbnail'], invalid: ['preview', 'Original'] },
    paramsRequest: { valid: [{ username: 'alice' }, { username: 'Alice With Spaces' }], invalid: [{ username: '' }, { username: tooLong(129) }, {}] },
    paramsResponse: {
      valid: [{ salt, opsLimit: 16, memLimitBytes: 268_435_456 }],
      invalid: [{ salt, opsLimit: 0, memLimitBytes: 268_435_456 }, { salt, opsLimit: 16, memLimitBytes: 0 }, { salt: 'A', opsLimit: 16, memLimitBytes: 268_435_456 }],
    },
    partsResponse: {
      valid: [
        {
          partSizeBytes: 8_388_608,
          uploadedParts: [{ partNumber: 1, sizeBytes: 8_388_608 }],
          partUrls: [{ partNumber: 2, url }],
          expiresAt: t.expires,
        },
        { partSizeBytes: 8_388_608, uploadedParts: [], partUrls: [], expiresAt: t.expires },
      ],
      invalid: [
        { partSizeBytes: 8_388_608, uploadedParts: [{ partNumber: 0, sizeBytes: 1 }], partUrls: [], expiresAt: t.expires },
        { partSizeBytes: 0, uploadedParts: [], partUrls: [], expiresAt: t.expires },
      ],
    },
    passwordChangeRequest: {
      valid: [...requests.passwordChange],
      invalid: [{ currentLoginKey: key, salt, opsLimit: 2, memLimitBytes: 8_388_608, loginKey: key }, { currentLoginKey: '', salt, opsLimit: 2, memLimitBytes: 8_388_608, loginKey: key, masterKeyByKek: wrapped }],
    },
    passwordChangeResponse: { valid: [{ session }], invalid: [{}, { session: { ...session, token: 'kdk_s_short' } }] },
    presignRequest: {
      valid: [
        { id: id.object, mediaId: id.media, variant: 'original', sizeBytes: 2_481_401 },
        { id: id.thumbnailObject, mediaId: id.media, variant: 'thumbnail', sizeBytes: 40 },
      ],
      invalid: [{ id: id.object, mediaId: id.media, variant: 'original', sizeBytes: 39 }, { id: id.object, mediaId: id.media, variant: 'preview', sizeBytes: 100 }],
    },
    presignResponse: {
      valid: [
        { mode: 'single', url, expiresAt: t.expires },
        { mode: 'multipart', partSizeBytes: 8_388_608, partUrls: [{ partNumber: 1, url }, { partNumber: 2, url }], expiresAt: t.expires },
      ],
      invalid: [{ mode: 'stream', url, expiresAt: t.expires }, { mode: 'multipart', partSizeBytes: 0, partUrls: [], expiresAt: t.expires }, { mode: 'single', expiresAt: t.expires }],
    },
    publicConfigSchema: {
      valid: [config, { ...config, signupMode: 'open', sharingEnabled: false, publicLinksEnabled: false, defaultQuotaBytes: 0 }],
      invalid: [{ ...config, apiVersion: 2 }, { ...config, signupMode: 'closed' }, { ...config, trashDays: 0 }],
    },
    publicKeyResponse: { valid: [{ userId: id.otherUser, username: 'bob', publicKey: key }], invalid: [{ userId: id.otherUser, username: 'bob' }, { userId: badId, username: 'bob', publicKey: key }] },
    publicLinkCreateRequest: {
      valid: [{ collectionId: id.collection, accessToken: key, expiry: '7d' }, { collectionId: id.collection, accessToken: key, expiry: 'one_view' }],
      invalid: [{ collectionId: id.collection, accessToken: key, expiry: '2d' }, { collectionId: id.collection, accessToken: '', expiry: '7d' }],
    },
    publicLinkDto: {
      valid: [publicLink, { ...publicLink, expiresAt: null, maxViews: 1, viewCount: 1 }],
      invalid: [{ ...publicLink, viewCount: -1 }, { ...publicLink, maxViews: 0 }],
    },
    publicLinkListResponse: { valid: [{ items: [publicLink] }, { items: [] }], invalid: [{}, { items: [{ ...publicLink, linkId: badId }] }] },
    publicLinkObjectParams: { valid: [{ linkId: id.link, objectId: id.object }], invalid: [{ linkId: id.link }, { linkId: badId, objectId: id.object }] },
    publicLinkParams: { valid: [{ linkId: id.link }], invalid: [{ linkId: badId }, {}] },
    recoveryCompleteRequest: {
      valid: [...requests.recovery],
      invalid: [
        { username: 'alice', recoveryLoginKey: key, deviceLabel: '', salt, opsLimit: 2, memLimitBytes: 8_388_608, loginKey: key, masterKeyByKek: wrapped },
        { username: 'alice', recoveryLoginKey: key, deviceLabel: tooLong(65), salt, opsLimit: 2, memLimitBytes: 8_388_608, loginKey: key, masterKeyByKek: wrapped },
        { username: 'alice', deviceLabel: 'Pixel 8', salt, opsLimit: 2, memLimitBytes: 8_388_608, loginKey: key, masterKeyByKek: wrapped },
      ],
    },
    recoveryStartRequest: { valid: [{ username: 'alice' }], invalid: [{ username: '' }, {}] },
    recoveryStartResponse: { valid: [{ userId: id.user, masterKeyByRecovery: wrapped }], invalid: [{ userId: badId, masterKeyByRecovery: wrapped }, { userId: id.user }] },
    revisionConflictDetails: { valid: [{ revision: 4, metadataBlob: blob }], invalid: [{ revision: 0, metadataBlob: blob }, { revision: 4 }] },
    sessionDto: {
      valid: [session],
      invalid: [{ ...session, token: `kdk_x_${sampleBytes(26, SAMPLE_TOKEN_BYTES)}` }, { ...session, token: 'kdk_s_short' }, { ...session, deviceLabel: '' }],
    },
    sessionResponse: { valid: [{ user }, { user: pendingUser }], invalid: [{}, { user: { ...user, role: 'owner' } }] },
    shareCreateRequest: { valid: [{ recipientUserId: id.otherUser, sealedCollectionKey: sealedBox }], invalid: [{ recipientUserId: id.otherUser }, { recipientUserId: badId, sealedCollectionKey: sealedBox }] },
    shareDto: { valid: [share], invalid: [{ ...share, createdAt: 'now' }, { recipientUserId: id.otherUser, createdAt: t.edited }] },
    shareListResponse: { valid: [{ items: [share] }, { items: [] }], invalid: [{}, { items: [{ ...share, recipientUserId: badId }] }] },
    shareParams: { valid: [{ id: id.collection, recipientUserId: id.otherUser }], invalid: [{ id: id.collection }, { id: badId, recipientUserId: id.otherUser }] },
    signInRequest: {
      valid: [{ username: 'alice', loginKey: key, deviceLabel: 'Pixel 8' }, { username: 'Alice', loginKey: key, deviceLabel: 'Firefox on Linux' }],
      invalid: [{ username: 'alice', loginKey: key, deviceLabel: '' }, { username: 'alice', loginKey: 'A', deviceLabel: 'Pixel 8' }, { username: '', loginKey: key, deviceLabel: 'Pixel 8' }],
    },
    signInResponse: {
      valid: [{ session, user, keyBundle }],
      invalid: [{ session, user }, { session, user, keyBundle: { ...keyBundle, publicKey: '' } }],
    },
    signUpRequest: {
      valid: [...requests.signUp],
      invalid: [
        { ...signUpObject, username: 'Alice' },
        { ...signUpObject, username: 'al' },
        { ...signUpObject, inviteToken: 'kdk_i_short' },
        { ...signUpObject, userId: badId },
        { ...signUpObject, opsLimit: 0 },
      ],
    },
    signUpResponse: { valid: [{ session, user }], invalid: [{ session }, { session, user: { ...user, usedBytes: -1 } }] },
    syncStateResponse: {
      valid: [
        {
          collections: [
            { id: id.collection, kind: 'home', role: 'owner', lastSeq: 42, tombstonesPrunedThroughSeq: 0, metaUpdatedAt: t.edited },
            { id: id.otherUser, kind: 'shared', role: 'viewer', lastSeq: 7, tombstonesPrunedThroughSeq: 3, metaUpdatedAt: t.created },
          ],
        },
        { collections: [] },
      ],
      invalid: [
        { collections: [{ id: id.collection, kind: 'home', role: 'owner', lastSeq: -1, tombstonesPrunedThroughSeq: 0, metaUpdatedAt: t.edited }] },
        {},
      ],
    },
    userDto: { valid: [user, pendingUser], invalid: [{ ...user, status: 'banned' }, { ...user, createdAt: '02/01/2026' }, { ...user, quotaBytes: -5 }] },
    userPublicKeyParams: { valid: [{ username: 'alice' }], invalid: [{ username: '' }, {}] },
  };
}

function dtoSampleCases(samples: Record<string, Samples>): GoldenCase[] {
  const schemas = Object.entries(contract as Record<string, unknown>).flatMap(([name, value]): [string, z.ZodType][] =>
    value instanceof z.ZodType ? [[name, value]] : [],
  );
  const exported = new Set(schemas.map(([name]) => name));
  const missing = [...exported].filter((name) => samples[name] === undefined);
  const extra = Object.keys(samples).filter((name) => !exported.has(name));
  if (missing.length > 0 || extra.length > 0) {
    throw new Error(`DTO samples out of sync: missing ${missing.join(', ')}; extra ${extra.join(', ')}`);
  }
  return schemas
    .map(([name, schema]) => {
      const sample = samples[name] ?? { valid: [], invalid: [] };
      sample.valid.forEach((value, index) => {
        if (!schema.safeParse(value).success) throw new Error(`${name} valid[${index}] does not parse`);
      });
      sample.invalid.forEach((value, index) => {
        if (schema.safeParse(value).success) throw new Error(`${name} invalid[${index}] parses`);
      });
      return { schema: name, valid: sample.valid, invalid: sample.invalid };
    })
    .sort((left, right) => (left.schema < right.schema ? -1 : left.schema > right.schema ? 1 : 0));
}

function outputsOf(goldenCase: GoldenCase): Json {
  const outputs = goldenCase['outputs'];
  if (typeof outputs !== 'object' || outputs === null || Array.isArray(outputs)) return null;
  return (outputs as { readonly [key: string]: Json })['request'] ?? null;
}

export async function generateGoldens(outputDirectory: string): Promise<readonly string[]> {
  const base = await createNodeSodium();
  const writer = new GoldenWriter(outputDirectory);

  await writer.cases(PATHS.sealedJson, sealedJsonCases(base));
  await writer.cases(PATHS.streamObjects, await streamObjectCases(base, writer));

  const signUps: SignUpResult[] = [];
  for (const spec of SIGN_UP_SPECS) signUps.push(await signUpCase(base, spec));
  await writer.cases(PATHS.signUp, signUps.map((result) => result.goldenCase));

  const firstAccount = requireFirst(signUps, 'first sign-up');
  const secondAccount = requireFirst(signUps.slice(1), 'second sign-up');
  const recoveries: GoldenCase[] = [];
  for (const spec of RECOVERY_SPECS) recoveries.push(await recoveryCase(base, firstAccount, spec));
  await writer.cases(PATHS.recovery, recoveries);

  const change = await passwordChangeCase(base, firstAccount);
  await writer.cases(PATHS.passwordChange, [change.goldenCase]);

  const locals = [
    await localAccountCase(base, {
      name: 'fresh-local-account',
      stored: storedFromSignUp(secondAccount.request),
      password: secondAccount.spec.password,
      username: secondAccount.spec.username,
      extras: { inviteToken: secondAccount.spec.inviteToken, deviceLabel: secondAccount.spec.deviceLabel },
    }),
    await localAccountCase(base, {
      name: 'local-account-after-password-change',
      stored: change.stored,
      password: change.password,
      username: firstAccount.spec.username,
      extras: { inviteToken: null, deviceLabel: 'Pixel 8' },
    }),
  ];
  await writer.cases(PATHS.localAccount, locals);

  const samples = dtoSamples({
    signUp: signUps.map((result) => outputsOf(result.goldenCase)),
    recovery: recoveries.map(outputsOf),
    passwordChange: [outputsOf(change.goldenCase)],
  });
  await writer.cases(PATHS.dtoSamples, dtoSampleCases(samples));

  return writer.written;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  const written = await generateGoldens(GOLDEN_DIRECTORY);
  process.stdout.write(`${written.length} golden files written to ${GOLDEN_DIRECTORY}\n`);
}
