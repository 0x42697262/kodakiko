import * as SecureStore from 'expo-secure-store';
import type { Sodium } from '@kodakiko/core';
import { sessionStoreKey } from '../../keys/storage-keys';
import { hashAccountKey } from '../account-key';
import type { SessionStore } from './session-store';

export function createSessionStore(deps: { readonly sodium: Sodium }): SessionStore {
  const storeKey = (accountKey: string): string =>
    sessionStoreKey(hashAccountKey(deps.sodium, accountKey));
  return {
    save: (accountKey, token) => SecureStore.setItemAsync(storeKey(accountKey), token),
    load: (accountKey) => SecureStore.getItemAsync(storeKey(accountKey)),
    clear: (accountKey) => SecureStore.deleteItemAsync(storeKey(accountKey)),
  };
}
