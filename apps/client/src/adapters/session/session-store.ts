import type { Sodium } from '@kodakiko/core';

export interface SessionStore {
  readonly save: (accountKey: string, token: string, persist: boolean) => Promise<void>;
  readonly load: (accountKey: string) => Promise<string | null>;
  readonly clear: (accountKey: string) => Promise<void>;
}

export declare function createSessionStore(deps: { readonly sodium: Sodium }): SessionStore;
