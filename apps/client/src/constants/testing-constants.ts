import type { DeviceSettingsValue } from '../services/settings/device-settings';
import { UI_SETTINGS_DEFAULTS } from './ui-settings-constants';

export const TEST_DEVICE_LABEL = 'Test client';

export const TEST_DEVICE_SETTINGS: DeviceSettingsValue = Object.freeze({
  encryptThumbnails: true,
  originalCacheCapBytes: 0,
  largeUploadsOnWifiOnly: false,
  largeUploadThresholdBytes: 104_857_600,
  locationForCaptures: false,
  autoLockMinutes: 5,
  ...UI_SETTINGS_DEFAULTS,
});
