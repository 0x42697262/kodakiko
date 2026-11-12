import type { Sodium } from '@kodakiko/core';
import { sessionStoreKey } from '../../keys/storage-keys';
import { hashAccountKey } from '../account-key';
import type { SessionStore } from './session-store';

export function createSessionStore(deps: { readonly sodium: Sodium }): SessionStore {
  const storeKey = (accountKey: string): string =>
    sessionStoreKey(hashAccountKey(deps.sodium, accountKey));
  return {
    save: async (accountKey, token, persist) => {
      const key = storeKey(accountKey);
      const [target, other] = persist
        ? [window.localStorage, window.sessionStorage]
        : [window.sessionStorage, window.localStorage];
      other.removeItem(key);
      target.setItem(key, token);
    },
    load: async (accountKey) => {
      const key = storeKey(accountKey);
      return window.sessionStorage.getItem(key) ?? window.localStorage.getItem(key);
    },
    clear: async (accountKey) => {
      const key = storeKey(accountKey);
      window.sessionStorage.removeItem(key);
      window.localStorage.removeItem(key);
    },
  };
}
