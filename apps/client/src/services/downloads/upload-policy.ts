import type { NetworkInfo } from '../../adapters/network/network';
import type { UploadPolicy } from '../sync/uploads';

export type WifiUploadPolicy = UploadPolicy & {
  readonly setWifi: (wifi: boolean) => void;
  readonly onChange: (listener: () => void) => () => void;
};

export type WifiUploadPolicyDeps = {
  readonly network: NetworkInfo;
  readonly settings: () => { readonly largeUploadsOnWifiOnly: boolean; readonly largeUploadThresholdBytes: number };
  readonly initialWifi: boolean;
};

export function createWifiUploadPolicy(deps: WifiUploadPolicyDeps): WifiUploadPolicy {
  let wifi = deps.initialWifi;
  const listeners = new Set<() => void>();

  function setWifi(next: boolean): void {
    if (next === wifi) return;
    wifi = next;
    for (const listener of [...listeners]) listener();
  }

  deps.network.subscribe(() => {
    deps.network
      .isWifi()
      .then(setWifi)
      .catch(() => undefined);
  });
  deps.network
    .isWifi()
    .then(setWifi)
    .catch(() => undefined);

  return {
    allowed: (sizeBytes) => {
      const settings = deps.settings();
      return sizeBytes <= settings.largeUploadThresholdBytes || !settings.largeUploadsOnWifiOnly || wifi;
    },
    setWifi,
    onChange: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}
