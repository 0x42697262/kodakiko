import type { DeviceSettingsValue } from '../services/settings/device-settings';
import { UI_SETTINGS_DEFAULTS } from './ui-settings-constants';

export const DEVICE_SETTINGS_DEFAULTS: DeviceSettingsValue = Object.freeze({
  encryptThumbnails: true,
  originalCacheCapBytes: 1_073_741_824,
  largeUploadsOnWifiOnly: true,
  largeUploadThresholdBytes: 104_857_600,
  locationForCaptures: false,
  autoLockMinutes: 15,
  ...UI_SETTINGS_DEFAULTS,
});
