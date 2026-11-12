import * as SecureStore from 'expo-secure-store';
import type { GlobalStore } from './global-store';

export function createGlobalStore(): GlobalStore {
  return {
    get: (key) => SecureStore.getItem(key),
    set: (key, value) => SecureStore.setItem(key, value),
    delete: (key) => SecureStore.deleteItemAsync(key),
  };
}
