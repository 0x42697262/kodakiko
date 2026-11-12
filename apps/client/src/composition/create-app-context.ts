import type { Sodium } from '@kodakiko/core';
import { systemClock, type Clock } from '../adapters/clock';
import { createFileStore } from '../adapters/filestore/filestore';
import { createHttpClient, type HttpClient } from '../adapters/http/http';
import { createRandomId } from '../adapters/ids';
import { createKeyVault } from '../adapters/keyvault/keyvault';
import { createLifecycle, type Lifecycle } from '../adapters/lifecycle/lifecycle';
import { createRowStore } from '../adapters/rowstore/rowstore';
import { createServerUrlStore, type ServerUrlStore } from '../adapters/server-url/server-url';
import { createSessionStore, type SessionStore } from '../adapters/session/session-store';
import { loadSodium } from '../adapters/sodium/sodium';
import type { FileStore } from '../adapters/filestore/filestore';
import type { KeyVault } from '../adapters/keyvault/keyvault';
import type { RowStore } from '../adapters/rowstore/rowstore';
import { STORAGE_KEYS } from '../keys/storage-keys';
import { EN } from '../text/en';
import {
  createAccountState,
  createObservable,
  type AccountStateStore,
  type Observable,
} from './account-state';

export type Services = Readonly<Record<string, never>>;

export type AppContext = {
  readonly sodium: Sodium;
  readonly serverUrl: ServerUrlStore;
  readonly sessionStore: SessionStore;
  readonly keyVault: KeyVault;
  readonly rowStore: RowStore;
  readonly fileStore: FileStore;
  readonly lifecycle: Lifecycle;
  readonly http: HttpClient;
  readonly clock: Clock;
  readonly randomId: () => string;
  readonly accountState: AccountStateStore;
  readonly activeAccountKey: Observable<string | null>;
  readonly services: Services;
};

export async function createAppContext(): Promise<AppContext> {
  const sodium = await loadSodium();
  const serverUrl = createServerUrlStore();
  const sessionStore = createSessionStore({ sodium });
  const keyVault = createKeyVault({ sodium });
  const rowStore = createRowStore({ sodium });
  const fileStore = createFileStore({
    sodium,
    onPersistResult: (granted) => {
      rowStore.kvSetJson(STORAGE_KEYS.deviceStoragePersisted, granted).catch(() => undefined);
    },
  });
  const lifecycle = createLifecycle();
  const accountState = createAccountState({
    kind: (await serverUrl.current()) === null ? 'noServer' : 'signedOut',
  });
  const activeAccountKey = createObservable<string | null>(null);
  const http = createHttpClient({
    baseUrl: async () => {
      const current = await serverUrl.current();
      if (current === null) throw new Error(EN['http.noServer']);
      return current;
    },
    sessionToken: async () => {
      const accountKey = activeAccountKey.get();
      return accountKey === null ? null : sessionStore.load(accountKey);
    },
    onSessionInvalid: () => accountState.set({ kind: 'signedOut' }),
  });
  return {
    sodium,
    serverUrl,
    sessionStore,
    keyVault,
    rowStore,
    fileStore,
    lifecycle,
    http,
    clock: systemClock,
    randomId: createRandomId(sodium),
    accountState,
    activeAccountKey,
    services: {},
  };
}
