import type { Sodium } from '@kodakiko/core';
import { hashAccountKey } from '../account-key';

export type VaultRecord = {
  readonly schemaVersion: 1;
  readonly wrappingKey: CryptoKey;
  readonly iv: Uint8Array<ArrayBuffer>;
  readonly wrappedMasterKey: Uint8Array<ArrayBuffer>;
};

export type VaultStorage = {
  readonly get: (id: string) => Promise<VaultRecord | null>;
  readonly put: (id: string, record: VaultRecord) => Promise<void>;
  readonly delete: (id: string) => Promise<void>;
};

export type PersistentVaultDeps = {
  readonly sodium: Sodium;
  readonly storage: VaultStorage;
  readonly subtle: SubtleCrypto;
  readonly randomValues: (bytes: Uint8Array<ArrayBuffer>) => Uint8Array<ArrayBuffer>;
};

export type PersistentVault = {
  readonly enablePersistence: (accountKey: string, masterKey: Uint8Array) => Promise<void>;
  readonly disablePersistence: (accountKey: string) => Promise<void>;
  readonly load: (accountKey: string) => Promise<Uint8Array | null>;
  readonly hasRecord: (accountKey: string) => Promise<boolean>;
};

const VAULT_SCHEMA_VERSION = 1;
const AES_KEY_BITS = 256;
const AES_IV_BYTES = 12;
const AES_ALGORITHM = 'AES-GCM';
const AD_PREFIX = 'kodakiko/v1/web-keep-unlocked/';

function additionalData(accountKey: string): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(`${AD_PREFIX}${accountKey}`);
}

export function createPersistentVault(deps: PersistentVaultDeps): PersistentVault {
  const idOf = (accountKey: string) => hashAccountKey(deps.sodium, accountKey);

  return {
    enablePersistence: async (accountKey, masterKey) => {
      const wrappingKey = await deps.subtle.generateKey({ name: AES_ALGORITHM, length: AES_KEY_BITS }, false, [
        'encrypt',
        'decrypt',
      ]);
      const iv = deps.randomValues(new Uint8Array(AES_IV_BYTES));
      const wrapped = await deps.subtle.encrypt(
        { name: AES_ALGORITHM, iv, additionalData: additionalData(accountKey) },
        wrappingKey,
        new Uint8Array(masterKey),
      );
      await deps.storage.put(idOf(accountKey), {
        schemaVersion: VAULT_SCHEMA_VERSION,
        wrappingKey,
        iv,
        wrappedMasterKey: new Uint8Array(wrapped),
      });
    },
    disablePersistence: (accountKey) => deps.storage.delete(idOf(accountKey)),
    load: async (accountKey) => {
      const id = idOf(accountKey);
      const record = await deps.storage.get(id);
      if (record === null) return null;
      try {
        const plain = await deps.subtle.decrypt(
          { name: AES_ALGORITHM, iv: record.iv, additionalData: additionalData(accountKey) },
          record.wrappingKey,
          record.wrappedMasterKey,
        );
        return new Uint8Array(plain);
      } catch {
        await deps.storage.delete(id);
        return null;
      }
    },
    hasRecord: async (accountKey) => (await deps.storage.get(idOf(accountKey))) !== null,
  };
}
