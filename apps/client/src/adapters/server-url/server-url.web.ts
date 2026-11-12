import type { ServerChoice, ServerUrlStore } from './server-url';

const INVALID_CHOICE: ServerChoice = { ok: false, reason: 'invalid' };

export function createServerUrlStore(): ServerUrlStore {
  return {
    current: async () => window.location.origin,
    choose: async () => INVALID_CHOICE,
  };
}
