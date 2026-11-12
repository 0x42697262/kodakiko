import { wipe, type Sodium } from '@kodakiko/core';
import type { KeyVault } from './keyvault';

export function createKeyVault(_deps: { readonly sodium: Sodium }): KeyVault {
  const keys = new Map<string, Uint8Array>();

  async function forget(accountKey: string): Promise<void> {
    const stored = keys.get(accountKey);
    if (stored !== undefined) wipe(stored);
    keys.delete(accountKey);
  }

  return {
    capabilities: async () => ({ persistent: false, biometric: false }),
    storeMasterKey: async (accountKey, masterKey) => {
      await forget(accountKey);
      keys.set(accountKey, masterKey.slice());
    },
    loadMasterKey: async (accountKey) => keys.get(accountKey)?.slice() ?? null,
    forget,
  };
}
