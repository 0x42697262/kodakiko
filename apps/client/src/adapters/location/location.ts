import type { DeviceLocation } from '../../features/capture/capture-tray-store';

export interface LocationProvider {
  readonly currentPosition: (timeoutMs: number) => Promise<DeviceLocation | null>;
}

export declare function createLocationProvider(): LocationProvider;
