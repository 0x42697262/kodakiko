import type { DeviceStorage } from './device-storage';

export async function readDeviceStorage(): Promise<DeviceStorage | null> {
  if (typeof navigator === 'undefined' || navigator.storage?.estimate === undefined) return null;
  const estimate = await navigator.storage.estimate().catch(() => null);
  if (estimate === null || estimate.quota === undefined) return null;
  return { free: Math.max(0, estimate.quota - (estimate.usage ?? 0)), total: estimate.quota };
}
