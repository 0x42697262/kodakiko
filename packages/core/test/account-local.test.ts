import { beforeAll, describe, expect, it } from 'vitest';
import {
  WrongPasswordError,
  createSignUpMaterial,
  deriveLoginMaterial,
  openKeyBundle,
  openRecovery,
  rebuildSignUpRequest,
  type KdfParams,
  type Sodium,
  type StoredLocalAccount,
} from '../src/index.ts';
import { createNodeSodium } from './sodium-node.ts';

const PASSWORD = 'correct horse battery staple';

let sodium: Sodium;
let counter = 0;

function fastParams(salt: Uint8Array): KdfParams {
  return { salt, opsLimit: 2, memLimitBytes: 8_388_608 };
}

function randomId(): string {
  counter += 1;
  return `id${String(counter).padStart(20, '0')}`;
}

beforeAll(async () => {
  sodium = await createNodeSodium();
});

async function localAccount() {
  const material = await createSignUpMaterial(
    sodium,
    { username: 'ada', password: PASSWORD, homeCollectionName: 'Journal', createdAtUtc: '2026-11-14T10:00:00.000Z', randomId },
    fastParams,
  );
  const { request } = material;
  const stored: StoredLocalAccount = {
    userId: request.userId,
    kdfParams: { salt: request.salt, opsLimit: request.opsLimit, memLimitBytes: request.memLimitBytes },
    masterKeyByKek: request.masterKeyByKek,
    masterKeyByRecovery: request.masterKeyByRecovery,
    recoveryKeyByMaster: request.recoveryKeyByMaster,
    publicKey: request.publicKey,
    privateKeyByMaster: request.privateKeyByMaster,
    homeCollection: request.homeCollection,
  };
  return { material, stored };
}

describe('rebuildSignUpRequest', () => {
  it('returns the stored wraps and ids unchanged with a fresh login key from the password', async () => {
    const { material, stored } = await localAccount();
    const rebuilt = await rebuildSignUpRequest(sodium, { password: PASSWORD, username: 'ada-renamed', stored });
    expect(rebuilt.userId).toBe(material.request.userId);
    expect(rebuilt.username).toBe('ada-renamed');
    expect(rebuilt.masterKeyByKek).toEqual(stored.masterKeyByKek);
    expect(rebuilt.masterKeyByRecovery).toEqual(stored.masterKeyByRecovery);
    expect(rebuilt.recoveryKeyByMaster).toEqual(stored.recoveryKeyByMaster);
    expect(rebuilt.privateKeyByMaster).toEqual(stored.privateKeyByMaster);
    expect(rebuilt.homeCollection).toEqual(stored.homeCollection);
    expect(rebuilt.recoveryLoginKey).toEqual(material.request.recoveryLoginKey);
    const login = await deriveLoginMaterial(sodium, PASSWORD, stored.kdfParams);
    expect(rebuilt.loginKey).toEqual(login.loginKey);
    expect(rebuilt.loginKey).toEqual(material.request.loginKey);
  });

  it('opens with the password and with the recovery words to the same master key', async () => {
    const { material, stored } = await localAccount();
    const rebuilt = await rebuildSignUpRequest(sodium, { password: PASSWORD, username: 'ada', stored });
    const login = await deriveLoginMaterial(sodium, PASSWORD, stored.kdfParams);
    const keys = openKeyBundle(sodium, login.kek, rebuilt.userId, {
      salt: rebuilt.salt,
      opsLimit: rebuilt.opsLimit,
      memLimitBytes: rebuilt.memLimitBytes,
      masterKeyByKek: rebuilt.masterKeyByKek,
      recoveryKeyByMaster: rebuilt.recoveryKeyByMaster,
      publicKey: rebuilt.publicKey,
      privateKeyByMaster: rebuilt.privateKeyByMaster,
    });
    expect(keys.masterKey).toEqual(material.secrets.masterKey);
    const recovered = openRecovery(sodium, material.recoveryWords, rebuilt.userId, rebuilt.masterKeyByRecovery);
    expect(recovered.masterKey).toEqual(material.secrets.masterKey);
  });

  it('rejects a wrong password', async () => {
    const { stored } = await localAccount();
    await expect(rebuildSignUpRequest(sodium, { password: 'not the password', username: 'ada', stored })).rejects.toBeInstanceOf(WrongPasswordError);
  });
});
