import * as Network from 'expo-network';
import type { NetworkInfo } from './network';

const UNMETERED: ReadonlySet<Network.NetworkStateType | undefined> = new Set([
  Network.NetworkStateType.WIFI,
  Network.NetworkStateType.ETHERNET,
]);

export function createNetworkInfo(): NetworkInfo {
  return {
    isWifi: async () => UNMETERED.has((await Network.getNetworkStateAsync()).type),
    subscribe: (listener) => {
      const subscription = Network.addNetworkStateListener(() => listener());
      return () => subscription.remove();
    },
  };
}
