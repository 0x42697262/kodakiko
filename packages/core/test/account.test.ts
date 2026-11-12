import { describe, expect, it, vi } from 'vitest';
import {
  AD_CONTEXTS,
  ARGON2ID_MIN_MEM_BYTES,
  ARGON2ID_START_MEM_BYTES,
  ARGON2ID_START_OPS,
  HostileServerParamsError,
  InvalidRecoveryWordsError,
  KdfUnavailableError,
  KEY_BYTES,
  SALT_BYTES,
  WrongPasswordError,
  WrongRecoveryWordsError,
  bytesEqual,
  checkPasswordPolicy,
  createPasswordChangeMaterial,
  createRecoveryCompletion,
  createSignUpMaterial,
  deriveForSignIn,
  deriveLoginMaterial,
  deriveWithAdaptiveParams,
  isValidUsername,
  meetsKdfFloor,
  nextKdfParamsAfterFailure,
  normalizeUsername,
  openJson,
  openKeyBundle,
  openRecovery,
  recoveryLoginKey,
  startKdfParams,
  unwrapKey,
  type KdfParams,
  type SignUpInput,
  type Sodium,
} from '../src/index.ts';
import { CollectionMetadataV1 } from '@kodakiko/contract';
import { createNodeSodium } from './sodium-node.ts';

const FAST_MEM_BYTES = 8_388_608;
const FAST_OPS = 2;
const MEBIBYTE = 1_048_576;
const PASSWORD = 'correct horse battery staple';
const OTHER_PASSWORD = 'a completely different one';
const USERNAME = 'alice';
const CREATED_AT = '2026-11-12T10:00:00.000Z';
const HOME_NAME = 'Journal';

function fastParams(salt: Uint8Array): KdfParams {
  return { salt, opsLimit: FAST_OPS, memLimitBytes: FAST_MEM_BYTES };
}

function idSource(): () => string {
  const ids = ['AAAAAAAAAAAAAAAAAAAAAA', 'BBBBBBBBBBBBBBBBBBBBBB'];
  let position = 0;
  return () => ids[position++] ?? 'CCCCCCCCCCCCCCCCCCCCCC';
}

function signUpInput(overrides: Partial<SignUpInput> = {}): SignUpInput {
  return {
    username: USERNAME,
    password: PASSWORD,
    homeCollectionName: HOME_NAME,
    createdAtUtc: CREATED_AT,
    randomId: idSource(),
    ...overrides,
  };
}

function paramsOf(request: {
  salt: Uint8Array;
  opsLimit: number;
  memLimitBytes: number;
}): KdfParams {
  return { salt: request.salt, opsLimit: request.opsLimit, memLimitBytes: request.memLimitBytes };
}

function fakeSodium(real: Sodium, argon2id: Sodium['argon2id']): Sodium {
  return { ...real, argon2id };
}

describe('username', () => {
  it('normalizes with NFKC, trim and lower case', () => {
    expect(normalizeUsername('  ＡLice  ')).toBe('alice');
    expect(normalizeUsername('Bob.Smith')).toBe('bob.smith');
  });

  it('validates against the contract pattern', () => {
    expect(isValidUsername('alice')).toBe(true);
    expect(isValidUsername('a.b_c-d')).toBe(true);
    expect(isValidUsername('ab')).toBe(false);
    expect(isValidUsername('a'.repeat(33))).toBe(false);
    expect(isValidUsername('Alice')).toBe(false);
    expect(isValidUsername('has space')).toBe(false);
  });
});

describe('password policy', () => {
  const COMBINING_PAIRS = 6;
  const composedPassword = (pairs: number) => 'e\u0301a\u0301'.repeat(pairs);

  it.each([
    ['11 characters', 'abcdefghijk', USERNAME, ['tooShort']],
    ['12 characters', 'abcdefghijkl', USERNAME, []],
    ['equal to the username', 'Alice-Wonderland', 'alice-wonderland', ['equalsUsername']],
    ['one repeated character', 'aaaaaaaaaaaa', USERNAME, ['singleRepeatedCharacter']],
    ['empty', '', USERNAME, ['tooShort']],
    ['combining characters count after NFKC (12)', composedPassword(COMBINING_PAIRS), USERNAME, []],
    [
      'combining characters count after NFKC (11)',
      composedPassword(COMBINING_PAIRS - 1) + 'e\u0301',
      USERNAME,
      ['tooShort'],
    ],
    ['astral characters count once', '😀😁😂🤣😃😄😅😆😉😊😋', USERNAME, ['tooShort']],
  ])('%s', (_name, password, username, expected) => {
    expect(checkPasswordPolicy(password, username)).toEqual(expected);
  });
});

describe('kdf params', () => {
  const salt = new Uint8Array(SALT_BYTES);

  it('starts at 256 MiB and 16 ops', () => {
    expect(startKdfParams(salt)).toEqual({
      salt,
      opsLimit: ARGON2ID_START_OPS,
      memLimitBytes: ARGON2ID_START_MEM_BYTES,
    });
  });

  it('halves memory and doubles ops after a failure, then runs out', () => {
    const second = nextKdfParamsAfterFailure(startKdfParams(salt));
    expect(second).toEqual({
      salt,
      opsLimit: ARGON2ID_START_OPS * 2,
      memLimitBytes: ARGON2ID_MIN_MEM_BYTES,
    });
    expect(nextKdfParamsAfterFailure(second as KdfParams)).toBeNull();
  });

  it('checks the floor', () => {
    expect(meetsKdfFloor({ memLimitBytes: ARGON2ID_MIN_MEM_BYTES, opsLimit: 32 })).toBe(true);
    expect(meetsKdfFloor({ memLimitBytes: ARGON2ID_START_MEM_BYTES, opsLimit: 16 })).toBe(true);
    expect(meetsKdfFloor({ memLimitBytes: ARGON2ID_MIN_MEM_BYTES, opsLimit: 31 })).toBe(false);
    expect(meetsKdfFloor({ memLimitBytes: ARGON2ID_START_MEM_BYTES, opsLimit: 15 })).toBe(false);
    expect(meetsKdfFloor({ memLimitBytes: 64 * MEBIBYTE, opsLimit: 64 })).toBe(false);
  });
});

describe('adaptive derivation', () => {
  it('ends at 128 MiB and 32 ops when 256 MiB throws', async () => {
    const real = await createNodeSodium();
    const output = new Uint8Array(KEY_BYTES).fill(7);
    const argon2id = vi.fn(async (input: Parameters<Sodium['argon2id']>[0]) => {
      if (input.memLimitBytes > ARGON2ID_MIN_MEM_BYTES) throw new Error('out of memory');
      return output.slice();
    });
    const salt = new Uint8Array(SALT_BYTES).fill(1);
    const { params } = await deriveWithAdaptiveParams(fakeSodium(real, argon2id), PASSWORD, salt);
    expect(params.memLimitBytes).toBe(ARGON2ID_MIN_MEM_BYTES);
    expect(params.opsLimit).toBe(ARGON2ID_START_OPS * 2);
    expect(argon2id).toHaveBeenCalledTimes(2);
  });

  it('throws KdfUnavailableError when every attempt throws', async () => {
    const real = await createNodeSodium();
    const argon2id = vi.fn(async () => {
      throw new Error('out of memory');
    });
    await expect(
      deriveWithAdaptiveParams(
        fakeSodium(real, argon2id),
        PASSWORD,
        new Uint8Array(SALT_BYTES),
      ),
    ).rejects.toBeInstanceOf(KdfUnavailableError);
  });

  it('refuses hostile server params before running argon2id', async () => {
    const real = await createNodeSodium();
    const argon2id = vi.fn(async () => new Uint8Array(KEY_BYTES));
    const hostile: KdfParams = {
      salt: new Uint8Array(SALT_BYTES),
      opsLimit: 64,
      memLimitBytes: 64 * MEBIBYTE,
    };
    await expect(deriveForSignIn(fakeSodium(real, argon2id), PASSWORD, hostile)).rejects.toBeInstanceOf(
      HostileServerParamsError,
    );
    expect(argon2id).not.toHaveBeenCalled();
  });

  it('refuses a salt of the wrong length before running argon2id', async () => {
    const real = await createNodeSodium();
    const argon2id = vi.fn(async () => new Uint8Array(KEY_BYTES));
    const shortSalt: KdfParams = {
      salt: new Uint8Array(SALT_BYTES - 1),
      opsLimit: ARGON2ID_START_OPS,
      memLimitBytes: ARGON2ID_START_MEM_BYTES,
    };
    await expect(
      deriveForSignIn(fakeSodium(real, argon2id), PASSWORD, shortSalt),
    ).rejects.toBeInstanceOf(HostileServerParamsError);
    expect(argon2id).not.toHaveBeenCalled();
  });

  it('derives deterministic kek and login key for the same inputs', async () => {
    const sodium = await createNodeSodium();
    const params = fastParams(new Uint8Array(SALT_BYTES).fill(3));
    const first = await deriveLoginMaterial(sodium, PASSWORD, params);
    const second = await deriveLoginMaterial(sodium, PASSWORD, params);
    const other = await deriveLoginMaterial(sodium, OTHER_PASSWORD, params);
    expect(bytesEqual(first.kek, second.kek)).toBe(true);
    expect(bytesEqual(first.loginKey, second.loginKey)).toBe(true);
    expect(bytesEqual(first.loginKey, first.kek)).toBe(false);
    expect(bytesEqual(first.loginKey, other.loginKey)).toBe(false);
  });
});

describe('sign-up material', () => {
  it('opens with openKeyBundle using a re-derived kek', async () => {
    const sodium = await createNodeSodium();
    const { request, secrets } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    expect(request.userId).toBe(secrets.userId);
    expect(request.username).toBe(USERNAME);
    expect(request.salt.length).toBe(SALT_BYTES);
    const material = await deriveLoginMaterial(sodium, PASSWORD, paramsOf(request));
    expect(bytesEqual(material.loginKey, request.loginKey)).toBe(true);
    const keys = openKeyBundle(sodium, material.kek, request.userId, {
      salt: request.salt,
      opsLimit: request.opsLimit,
      memLimitBytes: request.memLimitBytes,
      masterKeyByKek: request.masterKeyByKek,
      recoveryKeyByMaster: request.recoveryKeyByMaster,
      publicKey: request.publicKey,
      privateKeyByMaster: request.privateKeyByMaster,
    });
    expect(bytesEqual(keys.masterKey, secrets.masterKey)).toBe(true);
    expect(bytesEqual(keys.privateKey, secrets.privateKey)).toBe(true);
    expect(bytesEqual(keys.publicKey, secrets.publicKey)).toBe(true);
  });

  it('opens the home collection with the master key', async () => {
    const sodium = await createNodeSodium();
    const { request, secrets } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    expect(secrets.homeCollectionId).toBe(request.homeCollection.id);
    expect(secrets.homeCollectionId).not.toBe(secrets.userId);
    const collectionKey = unwrapKey(
      sodium,
      secrets.masterKey,
      request.homeCollection.keyByOwner,
      AD_CONTEXTS.collectionKeyByMaster,
      secrets.homeCollectionId,
    );
    expect(bytesEqual(collectionKey, secrets.homeCollectionKey)).toBe(true);
    const metadata = openJson(
      sodium,
      collectionKey,
      request.homeCollection.metadataBlob,
      CollectionMetadataV1,
      1,
      new Map(),
      AD_CONTEXTS.collectionMetadata,
      secrets.homeCollectionId,
    );
    expect(metadata.name).toBe(HOME_NAME);
    expect(metadata.createdAtUtc).toBe(CREATED_AT);
  });

  it('produces wraps of 73 bytes and 32-byte login keys', async () => {
    const sodium = await createNodeSodium();
    const { request } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    const wrapBytes = 73;
    for (const wrap of [
      request.masterKeyByKek,
      request.masterKeyByRecovery,
      request.recoveryKeyByMaster,
      request.privateKeyByMaster,
      request.homeCollection.keyByOwner,
    ]) {
      expect(wrap.length).toBe(wrapBytes);
    }
    expect(request.loginKey.length).toBe(KEY_BYTES);
    expect(request.recoveryLoginKey.length).toBe(KEY_BYTES);
    expect(request.publicKey.length).toBe(KEY_BYTES);
  });

  it('opens the recovery wraps with the 24 words', async () => {
    const sodium = await createNodeSodium();
    const { request, secrets, recoveryWords } = await createSignUpMaterial(
      sodium,
      signUpInput(),
      fastParams,
    );
    expect(recoveryWords).toHaveLength(24);
    const recovered = openRecovery(sodium, recoveryWords, request.userId, request.masterKeyByRecovery);
    expect(bytesEqual(recovered.masterKey, secrets.masterKey)).toBe(true);
    expect(bytesEqual(recoveryLoginKey(sodium, recovered.recoveryKey), request.recoveryLoginKey)).toBe(
      true,
    );
    const recoveryKeyBack = unwrapKey(
      sodium,
      secrets.masterKey,
      request.recoveryKeyByMaster,
      AD_CONTEXTS.recoveryKeyByMaster,
      request.userId,
    );
    expect(bytesEqual(recoveryKeyBack, recovered.recoveryKey)).toBe(true);
  });

  it('rejects a wrong password on open', async () => {
    const sodium = await createNodeSodium();
    const { request } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    const wrong = await deriveLoginMaterial(sodium, OTHER_PASSWORD, paramsOf(request));
    expect(() =>
      openKeyBundle(sodium, wrong.kek, request.userId, {
        salt: request.salt,
        opsLimit: request.opsLimit,
        memLimitBytes: request.memLimitBytes,
        masterKeyByKek: request.masterKeyByKek,
        recoveryKeyByMaster: request.recoveryKeyByMaster,
        publicKey: request.publicKey,
        privateKeyByMaster: request.privateKeyByMaster,
      }),
    ).toThrow(WrongPasswordError);
  });

  it('binds the wraps to the user id', async () => {
    const sodium = await createNodeSodium();
    const { request } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    const material = await deriveLoginMaterial(sodium, PASSWORD, paramsOf(request));
    expect(() =>
      openKeyBundle(sodium, material.kek, 'ZZZZZZZZZZZZZZZZZZZZZZ', {
        salt: request.salt,
        opsLimit: request.opsLimit,
        memLimitBytes: request.memLimitBytes,
        masterKeyByKek: request.masterKeyByKek,
        recoveryKeyByMaster: request.recoveryKeyByMaster,
        publicKey: request.publicKey,
        privateKeyByMaster: request.privateKeyByMaster,
      }),
    ).toThrow(WrongPasswordError);
  });
});

describe('password change', () => {
  it('re-wraps the master key under the new password', async () => {
    const sodium = await createNodeSodium();
    const { request, secrets } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    const changed = await createPasswordChangeMaterial(
      sodium,
      {
        userId: request.userId,
        currentPassword: PASSWORD,
        newPassword: OTHER_PASSWORD,
        currentParams: paramsOf(request),
        cachedMasterKeyByKek: request.masterKeyByKek,
      },
      fastParams,
    );
    expect(bytesEqual(changed.currentLoginKey, request.loginKey)).toBe(true);
    expect(bytesEqual(changed.salt, request.salt)).toBe(false);
    const material = await deriveLoginMaterial(sodium, OTHER_PASSWORD, paramsOf(changed));
    expect(bytesEqual(material.loginKey, changed.loginKey)).toBe(true);
    const masterKey = unwrapKey(
      sodium,
      material.kek,
      changed.masterKeyByKek,
      AD_CONTEXTS.masterKeyByKek,
      request.userId,
    );
    expect(bytesEqual(masterKey, secrets.masterKey)).toBe(true);
  });

  it('throws WrongPasswordError for a wrong current password and returns nothing', async () => {
    const sodium = await createNodeSodium();
    const { request } = await createSignUpMaterial(sodium, signUpInput(), fastParams);
    const argon2id = vi.fn(sodium.argon2id);
    await expect(
      createPasswordChangeMaterial(
        fakeSodium(sodium, argon2id),
        {
          userId: request.userId,
          currentPassword: OTHER_PASSWORD,
          newPassword: 'yet another password',
          currentParams: paramsOf(request),
          cachedMasterKeyByKek: request.masterKeyByKek,
        },
        fastParams,
      ),
    ).rejects.toBeInstanceOf(WrongPasswordError);
    expect(argon2id).toHaveBeenCalledTimes(1);
  });
});

describe('recovery', () => {
  it('builds a completion that signs in with the new password', async () => {
    const sodium = await createNodeSodium();
    const { request, secrets, recoveryWords } = await createSignUpMaterial(
      sodium,
      signUpInput(),
      fastParams,
    );
    const opened = openRecovery(sodium, recoveryWords, request.userId, request.masterKeyByRecovery);
    const completion = await createRecoveryCompletion(
      sodium,
      {
        userId: request.userId,
        newPassword: OTHER_PASSWORD,
        masterKey: opened.masterKey,
        recoveryKey: opened.recoveryKey,
      },
      fastParams,
    );
    expect(bytesEqual(completion.recoveryLoginKey, request.recoveryLoginKey)).toBe(true);
    const material = await deriveLoginMaterial(sodium, OTHER_PASSWORD, paramsOf(completion));
    expect(bytesEqual(material.loginKey, completion.loginKey)).toBe(true);
    const masterKey = unwrapKey(
      sodium,
      material.kek,
      completion.masterKeyByKek,
      AD_CONTEXTS.masterKeyByKek,
      request.userId,
    );
    expect(bytesEqual(masterKey, secrets.masterKey)).toBe(true);
  });

  it('throws WrongRecoveryWordsError for valid words of another key', async () => {
    const sodium = await createNodeSodium();
    const first = await createSignUpMaterial(sodium, signUpInput({ username: 'alice' }), fastParams);
    const second = await createSignUpMaterial(sodium, signUpInput({ username: 'bobby' }), fastParams);
    expect(() =>
      openRecovery(
        sodium,
        second.recoveryWords,
        first.request.userId,
        first.request.masterKeyByRecovery,
      ),
    ).toThrow(WrongRecoveryWordsError);
  });

  it('throws InvalidRecoveryWordsError for an unknown word', async () => {
    const sodium = await createNodeSodium();
    const { request, recoveryWords } = await createSignUpMaterial(
      sodium,
      signUpInput(),
      fastParams,
    );
    const tampered = [...recoveryWords];
    tampered[0] = 'zzzzzz';
    expect(() =>
      openRecovery(sodium, tampered, request.userId, request.masterKeyByRecovery),
    ).toThrow(InvalidRecoveryWordsError);
  });
});
