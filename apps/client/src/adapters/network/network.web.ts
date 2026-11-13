import type { NetworkInfo } from './network';

export function createNetworkInfo(): NetworkInfo {
  return {
    isWifi: async () => true,
    subscribe: (listener) => {
      window.addEventListener('online', listener);
      return () => window.removeEventListener('online', listener);
    },
  };
}
