import { beforeEach, describe, expect, it } from 'vitest';
import type { Sodium } from '@kodakiko/core';
import { createNodeSodium } from '../../../packages/core/test/sodium-node';
import { createPersistentVault, type VaultRecord, type VaultStorage } from '../src/adapters/keyvault/persistent-vault';

let sodium: Sodium;
let records: Map<string, VaultRecord>;
let storage: VaultStorage;

beforeEach(async () => {
  sodium = await createNodeSodium();
  records = new Map();
  storage = {
    get: async (id) => records.get(id) ?? null,
    put: async (id, record) => {
      records.set(id, record);
    },
    delete: async (id) => {
      records.delete(id);
    },
  };
});

function vault() {
  return createPersistentVault({ sodium, storage, subtle: globalThis.crypto.subtle, randomValues: (bytes) => globalThis.crypto.getRandomValues(bytes) });
}

describe('keep-unlocked vault', () => {
  it('enable then load returns the same master key with a non-extractable wrapping key', async () => {
    const masterKey = sodium.randomBytes(32);
    const subject = vault();
    await subject.enablePersistence('account-a', masterKey);
    expect(await subject.hasRecord('account-a')).toBe(true);
    expect(await subject.load('account-a')).toEqual(masterKey);
    const [record] = [...records.values()];
    expect(record?.wrappingKey.extractable).toBe(false);
    expect(record?.schemaVersion).toBe(1);
  });

  it('a tampered record loads as null and is deleted', async () => {
    const subject = vault();
    await subject.enablePersistence('account-a', sodium.randomBytes(32));
    const [id, record] = [...records.entries()][0] ?? [];
    if (id === undefined || record === undefined) throw new Error('no record');
    const tampered = record.wrappedMasterKey.slice();
    tampered[0] = (tampered[0] ?? 0) ^ 1;
    records.set(id, { ...record, wrappedMasterKey: tampered });
    expect(await subject.load('account-a')).toBeNull();
    expect(records.size).toBe(0);
  });

  it('a record for one account does not open another', async () => {
    const subject = vault();
    await subject.enablePersistence('account-a', sodium.randomBytes(32));
    expect(await subject.load('account-b')).toBeNull();
  });

  it('disable removes the record', async () => {
    const subject = vault();
    await subject.enablePersistence('account-a', sodium.randomBytes(32));
    await subject.disablePersistence('account-a');
    expect(await subject.load('account-a')).toBeNull();
    expect(await subject.hasRecord('account-a')).toBe(false);
  });
});
