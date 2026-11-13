import { openDB } from 'idb';
import { wipe, type Sodium } from '@kodakiko/core';
import type { KeyVault } from './keyvault';
import { createPersistentVault, type VaultRecord, type VaultStorage } from './persistent-vault';

const VAULT_DATABASE = 'kodakiko-keys';
const VAULT_DATABASE_VERSION = 1;
const VAULT_STORE = 'vault';

function createIdbVaultStorage(): VaultStorage {
  let opened: ReturnType<typeof openDB> | null = null;
  function database(): ReturnType<typeof openDB> {
    opened ??= openDB(VAULT_DATABASE, VAULT_DATABASE_VERSION, {
      upgrade: (db) => {
        if (!db.objectStoreNames.contains(VAULT_STORE)) db.createObjectStore(VAULT_STORE);
      },
    });
    return opened;
  }
  return {
    get: async (id) => ((await (await database()).get(VAULT_STORE, id)) as VaultRecord | undefined) ?? null,
    put: async (id, record) => {
      await (await database()).put(VAULT_STORE, record, id);
    },
    delete: async (id) => {
      await (await database()).delete(VAULT_STORE, id);
    },
  };
}

export function createKeyVault(deps: { readonly sodium: Sodium }): KeyVault {
  const keys = new Map<string, Uint8Array>();
  const persistent = createPersistentVault({
    sodium: deps.sodium,
    storage: createIdbVaultStorage(),
    subtle: globalThis.crypto.subtle,
    randomValues: (bytes) => globalThis.crypto.getRandomValues(bytes),
  });

  function forgetMemory(accountKey: string): void {
    const stored = keys.get(accountKey);
    if (stored !== undefined) wipe(stored);
    keys.delete(accountKey);
  }

  return {
    capabilities: async (accountKey) => ({
      persistent: accountKey === undefined ? false : await persistent.hasRecord(accountKey).catch(() => false),
      biometric: false,
    }),
    storeMasterKey: async (accountKey, masterKey) => {
      forgetMemory(accountKey);
      keys.set(accountKey, masterKey.slice());
    },
    loadMasterKey: async (accountKey) => {
      const stored = await persistent.load(accountKey).catch(() => null);
      return stored ?? keys.get(accountKey)?.slice() ?? null;
    },
    forget: async (accountKey) => {
      forgetMemory(accountKey);
      await persistent.disablePersistence(accountKey).catch(() => undefined);
    },
    keepUnlocked: {
      enable: (accountKey, masterKey) => persistent.enablePersistence(accountKey, masterKey),
      disable: (accountKey) => persistent.disablePersistence(accountKey),
    },
  };
}
