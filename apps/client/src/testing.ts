import { startKdfParams, type Sodium } from '@kodakiko/core';
import { systemClock } from './adapters/clock';
import { createHttpClient } from './adapters/http/http';
import { createRandomId } from './adapters/ids';
import { createKeyVault } from './adapters/keyvault/keyvault.web';
import { createMemoryFileStore } from './adapters/memory/memory-file-store';
import { createMemoryGlobalStore } from './adapters/memory/memory-global-store';
import { createMemoryRowStore } from './adapters/memory/memory-row-store';
import { createMemorySessionStore } from './adapters/memory/memory-session-store';
import { createAccountState, createObservable } from './composition/account-state';
import { TEST_DEVICE_LABEL } from './constants/testing-constants';
import {
  createAuthService,
  type AuthService,
  type AuthServiceContext,
} from './services/auth-service';

export type TestAuthContextInput = {
  readonly baseUrl: string;
  readonly fetcher: typeof fetch;
  readonly sodium: Sodium;
};

export type TestAuthContext = AuthServiceContext & { readonly auth: AuthService };

export function createTestAuthContext(input: TestAuthContextInput): TestAuthContext {
  const sessionStore = createMemorySessionStore();
  const accountState = createAccountState({ kind: 'signedOut' });
  const activeAccountKey = createObservable<string | null>(null);
  const http = createHttpClient({
    baseUrl: async () => input.baseUrl,
    sessionToken: async () => {
      const accountKey = activeAccountKey.get();
      return accountKey === null ? null : sessionStore.load(accountKey);
    },
    onSessionInvalid: () => accountState.set({ kind: 'signedOut' }),
    fetcher: input.fetcher,
  });
  const context: AuthServiceContext = {
    sodium: input.sodium,
    http,
    serverUrl: {
      current: async () => input.baseUrl,
      choose: async () => ({ ok: false, reason: 'invalid' }),
    },
    sessionStore,
    keyVault: createKeyVault({ sodium: input.sodium }),
    rowStore: createMemoryRowStore(),
    fileStore: createMemoryFileStore(),
    globalStore: createMemoryGlobalStore(),
    lifecycle: {
      afterInteractions: async () => undefined,
      subscribeActive: () => () => undefined,
      subscribeOnline: () => () => undefined,
    },
    clock: systemClock,
    randomId: createRandomId(input.sodium),
    accountState,
    activeAccountKey,
    inviteToken: createObservable<string | null>(null),
    config: {
      get: () => null,
      refresh: async () => null,
      accept: () => undefined,
      subscribe: () => () => undefined,
    },
    deviceLabel: () => TEST_DEVICE_LABEL,
    kdfStartParams: startKdfParams,
    showSecuring: () => undefined,
    dismissSecuring: () => undefined,
  };
  return { ...context, auth: createAuthService(context) };
}

export { createWrappersSodium } from './adapters/sodium/sodium-wrappers';
