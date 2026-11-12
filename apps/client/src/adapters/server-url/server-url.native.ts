import * as SecureStore from 'expo-secure-store';
import { HTTP_TIMEOUT_MS } from '../../constants/http-constants';
import { SECURE_STORE_KEYS } from '../../keys/storage-keys';
import {
  createServerUrlStore as createNeutralServerUrlStore,
  type ServerUrlStore,
} from './server-url.ts';

export function createServerUrlStore(): ServerUrlStore {
  return createNeutralServerUrlStore({
    fetcher: (input, init) => fetch(input, init),
    timeoutMs: HTTP_TIMEOUT_MS,
    allowLocalHttp: __DEV__,
    storage: {
      get: () => SecureStore.getItemAsync(SECURE_STORE_KEYS.server),
      set: (value) => SecureStore.setItemAsync(SECURE_STORE_KEYS.server, value),
    },
  });
}
