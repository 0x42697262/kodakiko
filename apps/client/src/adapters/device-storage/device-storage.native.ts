import { Paths } from 'expo-file-system';
import type { DeviceStorage } from './device-storage';

export async function readDeviceStorage(): Promise<DeviceStorage | null> {
  try {
    return { free: Paths.availableDiskSpace, total: Paths.totalDiskSpace };
  } catch {
    return null;
  }
}
