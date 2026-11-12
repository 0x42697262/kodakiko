import type { GlobalStore } from '../global-store/global-store';

export function createMemoryGlobalStore(): GlobalStore {
  const values = new Map<string, string>();
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => {
      values.set(key, value);
    },
    delete: async (key) => {
      values.delete(key);
    },
  };
}
