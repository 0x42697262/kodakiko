import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import type { Sodium } from '@kodakiko/core';
import { hashAccountKey } from '../account-key';
import type { KeyVault } from './keyvault';
import { createSecureKeyVault } from './secure-keyvault';

export function createKeyVault(deps: { readonly sodium: Sodium }): KeyVault {
  return createSecureKeyVault({
    secureStore: SecureStore,
    localAuthentication: LocalAuthentication,
    accountHash: (accountKey) => hashAccountKey(deps.sodium, accountKey),
  });
}
