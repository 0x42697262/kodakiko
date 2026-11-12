import { bytesSchema, decodeBytes, encodeBytes } from '@kodakiko/contract';
import { KEY_BYTES } from '@kodakiko/core';
import {
  KEY_AUTH_MODES,
  masterKeyAuthModeStoreKey,
  masterKeyStoreKey,
} from '../../keys/storage-keys';
import type { KeyVault } from './keyvault';

export type SecureStorePort = {
  readonly canUseBiometricAuthentication: () => boolean;
  readonly getItemAsync: (
    key: string,
    options?: { readonly requireAuthentication?: boolean; readonly authenticationPrompt?: string },
  ) => Promise<string | null>;
  readonly setItemAsync: (
    key: string,
    value: string,
    options?: { readonly requireAuthentication?: boolean },
  ) => Promise<void>;
  readonly deleteItemAsync: (key: string) => Promise<void>;
};

export type LocalAuthenticationPort = {
  readonly getEnrolledLevelAsync: () => Promise<number>;
  readonly authenticateAsync: (options: {
    readonly promptMessage: string;
  }) => Promise<{ readonly success: boolean }>;
};

export type SecureKeyVaultDeps = {
  readonly secureStore: SecureStorePort;
  readonly localAuthentication: LocalAuthenticationPort;
  readonly accountHash: (accountKey: string) => string;
};

const ENROLLED_LEVEL_NONE = 0;

function decodeMasterKey(stored: string): Uint8Array | null {
  const parsed = bytesSchema.safeParse(stored);
  if (!parsed.success) return null;
  try {
    const bytes = decodeBytes(parsed.data);
    return bytes.length === KEY_BYTES ? bytes : null;
  } catch {
    return null;
  }
}

export function createSecureKeyVault(deps: SecureKeyVaultDeps): KeyVault {
  const { secureStore, localAuthentication } = deps;
  const keyItem = (accountKey: string): string => masterKeyStoreKey(deps.accountHash(accountKey));
  const modeItem = (accountKey: string): string =>
    masterKeyAuthModeStoreKey(deps.accountHash(accountKey));

  async function forget(accountKey: string): Promise<void> {
    await secureStore.deleteItemAsync(keyItem(accountKey));
    await secureStore.deleteItemAsync(modeItem(accountKey));
  }

  async function readMode(accountKey: string): Promise<string | null> {
    try {
      return await secureStore.getItemAsync(modeItem(accountKey));
    } catch {
      return null;
    }
  }

  async function authenticated(prompt: string): Promise<boolean> {
    try {
      return (await localAuthentication.authenticateAsync({ promptMessage: prompt })).success;
    } catch {
      return false;
    }
  }

  async function hasSecureScreenLock(): Promise<boolean> {
    try {
      return (await localAuthentication.getEnrolledLevelAsync()) !== ENROLLED_LEVEL_NONE;
    } catch {
      return false;
    }
  }

  return {
    capabilities: async () => {
      const locked = await hasSecureScreenLock();
      return {
        persistent: locked,
        biometric: locked && secureStore.canUseBiometricAuthentication(),
      };
    },
    storeMasterKey: async (accountKey, masterKey) => {
      if (!(await hasSecureScreenLock())) return;
      const biometric = secureStore.canUseBiometricAuthentication();
      const mode = biometric ? KEY_AUTH_MODES.biometric : KEY_AUTH_MODES.device;
      await secureStore.setItemAsync(modeItem(accountKey), mode);
      await secureStore.setItemAsync(keyItem(accountKey), encodeBytes(masterKey), {
        requireAuthentication: biometric,
      });
    },
    loadMasterKey: async (accountKey, prompt) => {
      if (!(await hasSecureScreenLock())) return null;
      const biometric = (await readMode(accountKey)) === KEY_AUTH_MODES.biometric;
      if (!biometric && !(await authenticated(prompt))) return null;
      let stored: string | null;
      try {
        stored = await secureStore.getItemAsync(keyItem(accountKey), {
          requireAuthentication: biometric,
          authenticationPrompt: prompt,
        });
      } catch {
        return null;
      }
      const masterKey = stored === null ? null : decodeMasterKey(stored);
      if (masterKey === null) await forget(accountKey);
      return masterKey;
    },
    forget,
  };
}
