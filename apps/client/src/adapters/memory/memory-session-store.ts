import type { SessionStore } from '../session/session-store';

export function createMemorySessionStore(): SessionStore {
  const tokens = new Map<string, string>();
  return {
    save: async (accountKey, token) => {
      tokens.set(accountKey, token);
    },
    load: async (accountKey) => tokens.get(accountKey) ?? null,
    clear: async (accountKey) => {
      tokens.delete(accountKey);
    },
  };
}
