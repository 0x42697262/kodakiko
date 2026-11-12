import type { GlobalStore } from './global-store';

export function createGlobalStore(): GlobalStore {
  return {
    get: (key) => window.localStorage.getItem(key),
    set: (key, value) => window.localStorage.setItem(key, value),
    delete: async (key) => window.localStorage.removeItem(key),
  };
}
