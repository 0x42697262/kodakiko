import { describe, expect, it } from 'vitest';
import { encodeBytes } from '@kodakiko/contract';
import { KEY_BYTES } from '@kodakiko/core';
import {
  createSecureKeyVault,
  type LocalAuthenticationPort,
  type SecureStorePort,
} from '../src/adapters/keyvault/secure-keyvault';

const ACCOUNT_KEY = 'journal.example.test/user-a';
const ACCOUNT_HASH = '0123456789abcdef';
const MASTER_KEY_ITEM = `kodakiko.mk.${ACCOUNT_HASH}`;
const AUTH_MODE_ITEM = `kodakiko.mkauth.${ACCOUNT_HASH}`;
const PROMPT = 'Unlock Kodakiko';
const MASTER_KEY = Uint8Array.from({ length: KEY_BYTES }, (_value, index) => index + 1);

type Harness = {
  readonly items: Map<string, string>;
  readonly setOptions: Map<string, { requireAuthentication?: boolean } | undefined>;
  readonly state: {
    canUseBiometric: boolean;
    authenticateResult: 'success' | 'cancel' | 'throw';
    getItemFailure: 'none' | 'throw';
    authenticateCalls: number;
  };
  readonly vault: ReturnType<typeof createSecureKeyVault>;
};

function buildHarness(canUseBiometric: boolean): Harness {
  const items = new Map<string, string>();
  const setOptions = new Map<string, { requireAuthentication?: boolean } | undefined>();
  const state: Harness['state'] = {
    canUseBiometric,
    authenticateResult: 'success',
    getItemFailure: 'none',
    authenticateCalls: 0,
  };
  const secureStore: SecureStorePort = {
    canUseBiometricAuthentication: () => state.canUseBiometric,
    getItemAsync: async (key) => {
      if (state.getItemFailure === 'throw' && key === MASTER_KEY_ITEM) {
        throw new Error('user canceled');
      }
      return items.get(key) ?? null;
    },
    setItemAsync: async (key, value, options) => {
      items.set(key, value);
      setOptions.set(key, options);
    },
    deleteItemAsync: async (key) => {
      items.delete(key);
    },
  };
  const localAuthentication: LocalAuthenticationPort = {
    authenticateAsync: async () => {
      state.authenticateCalls += 1;
      if (state.authenticateResult === 'throw') throw new Error('lockout');
      return { success: state.authenticateResult === 'success' };
    },
  };
  const vault = createSecureKeyVault({
    secureStore,
    localAuthentication,
    accountHash: () => ACCOUNT_HASH,
  });
  return { items, setOptions, state, vault };
}

describe('secure key vault', () => {
  it('stores the key with the mode actually used and requires authentication only for biometrics', async () => {
    const biometric = buildHarness(true);
    await biometric.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    expect(biometric.items.get(AUTH_MODE_ITEM)).toBe('biometric');
    expect(biometric.setOptions.get(MASTER_KEY_ITEM)?.requireAuthentication).toBe(true);

    const device = buildHarness(false);
    await device.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    expect(device.items.get(AUTH_MODE_ITEM)).toBe('device');
    expect(device.setOptions.get(MASTER_KEY_ITEM)?.requireAuthentication).toBe(false);
  });

  it('round-trips the key without an extra prompt in biometric mode', async () => {
    const harness = buildHarness(true);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toEqual(MASTER_KEY);
    expect(harness.state.authenticateCalls).toBe(0);
  });

  it('still prompts for the device credential when a fingerprint is enrolled after storing', async () => {
    const harness = buildHarness(false);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    harness.state.canUseBiometric = true;
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toEqual(MASTER_KEY);
    expect(harness.state.authenticateCalls).toBe(1);
  });

  it('treats a missing mode record as device mode and prompts', async () => {
    const harness = buildHarness(false);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    harness.items.delete(AUTH_MODE_ITEM);
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toEqual(MASTER_KEY);
    expect(harness.state.authenticateCalls).toBe(1);
  });

  it('returns null without forgetting when the user cancels or authentication throws', async () => {
    const harness = buildHarness(false);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    harness.state.authenticateResult = 'cancel';
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toBeNull();
    harness.state.authenticateResult = 'throw';
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toBeNull();
    expect(harness.items.has(MASTER_KEY_ITEM)).toBe(true);
    expect(harness.items.has(AUTH_MODE_ITEM)).toBe(true);
  });

  it('returns null without forgetting when reading the item throws (cancel, lockout)', async () => {
    const harness = buildHarness(true);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    harness.state.getItemFailure = 'throw';
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toBeNull();
    expect(harness.items.has(MASTER_KEY_ITEM)).toBe(true);
    expect(harness.items.has(AUTH_MODE_ITEM)).toBe(true);
  });

  it('forgets both items when the stored key is gone or invalidated (null)', async () => {
    const harness = buildHarness(true);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    harness.items.delete(MASTER_KEY_ITEM);
    await expect(harness.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toBeNull();
    expect(harness.items.has(MASTER_KEY_ITEM)).toBe(false);
    expect(harness.items.has(AUTH_MODE_ITEM)).toBe(false);
  });

  it('forgets both items when the stored value is not base64url or not KEY_BYTES long', async () => {
    const notBase64 = buildHarness(true);
    await notBase64.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    notBase64.items.set(MASTER_KEY_ITEM, '!!not base64url!!');
    await expect(notBase64.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toBeNull();
    expect(notBase64.items.size).toBe(0);

    const wrongLength = buildHarness(true);
    await wrongLength.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    wrongLength.items.set(MASTER_KEY_ITEM, encodeBytes(new Uint8Array(KEY_BYTES - 1)));
    await expect(wrongLength.vault.loadMasterKey(ACCOUNT_KEY, PROMPT)).resolves.toBeNull();
    expect(wrongLength.items.size).toBe(0);
  });

  it('reports capabilities from the secure store biometric check', async () => {
    const harness = buildHarness(false);
    await expect(harness.vault.capabilities()).resolves.toEqual({
      persistent: true,
      biometric: false,
    });
    harness.state.canUseBiometric = true;
    await expect(harness.vault.capabilities()).resolves.toEqual({
      persistent: true,
      biometric: true,
    });
  });

  it('forget deletes both items', async () => {
    const harness = buildHarness(true);
    await harness.vault.storeMasterKey(ACCOUNT_KEY, MASTER_KEY);
    await harness.vault.forget(ACCOUNT_KEY);
    expect(harness.items.size).toBe(0);
  });
});
