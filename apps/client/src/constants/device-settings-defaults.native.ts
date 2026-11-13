import type { DeviceSettingsValue } from '../services/settings/device-settings';
import { UI_SETTINGS_DEFAULTS } from './ui-settings-constants';

export const DEVICE_SETTINGS_DEFAULTS: DeviceSettingsValue = Object.freeze({
  encryptThumbnails: false,
  originalCacheCapBytes: 2_147_483_648,
  largeUploadsOnWifiOnly: true,
  largeUploadThresholdBytes: 104_857_600,
  locationForCaptures: false,
  autoLockMinutes: 5,
  ...UI_SETTINGS_DEFAULTS,
});
