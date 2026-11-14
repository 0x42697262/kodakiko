import { beforeEach, describe, expect, it } from 'vitest';
import { z } from 'zod';
import { DEVICE_SETTINGS_DEFAULTS as NATIVE_DEFAULTS } from '../src/constants/device-settings-defaults.native';
import { DEVICE_SETTINGS_DEFAULTS as WEB_DEFAULTS } from '../src/constants/device-settings-defaults.web';
import { STORAGE_KEYS } from '../src/keys/storage-keys';
import type { RowStore } from '../src/adapters/rowstore/rowstore';
import {
  createDeviceSettings,
  deviceSettingsSchema,
  type DeviceSettingsValue,
} from '../src/services/settings/device-settings';
import { createMemoryRowStore } from './support';

const ACCOUNT_KEY = 'host/user';
const MIN_THRESHOLD_BYTES = 1_048_576;
const STORED_SHAPE = z.record(z.string(), z.unknown());

const SPEC_22_DEFAULTS = {
  appTheme: 'system',
  dateFormat: 'system',
  relativeDates: true,
  startScreen: 'gallery',
  galleryDisplayMode: 'comfortableGrid',
  galleryColumnsPortrait: 0,
  galleryColumnsLandscape: 0,
  galleryGroupByDay: true,
  galleryBadges: { video: true, photoCount: true, note: true, pending: true, keptOffline: false, favorite: true },
  galleryShowTagTabs: true,
  galleryShowCounts: false,
  gallerySort: { key: 'takenAt', descending: true },
  galleryFilters: { video: 'off', note: 'off', multiple: 'off', shared: 'off', pending: 'off', keptOffline: 'off', favorite: 'off' },
  hideShared: false,
  onThisDay: true,
  confirmTrash: true,
  captureStartCamera: 'back',
  captureKeepScreenOn: true,
  hideNotePreviews: false,
  secureScreen: true,
  fullScreen: true,
  hideBarsOnScroll: true,
  showTechnicalDetails: false,
  holdToRecord: false,
  holdToRecordMaxSeconds: 15,
  cameraApp: 'kodakiko',
} as const;

let rowStore: RowStore;

beforeEach(async () => {
  rowStore = createMemoryRowStore();
  await rowStore.open(ACCOUNT_KEY);
});

async function storedJson(): Promise<Record<string, unknown> | null> {
  return rowStore.kvGetJson(STORAGE_KEYS.settingsDevice, STORED_SHAPE);
}

describe('device settings defaults', () => {
  it('has the Android defaults of spec 09 §4.2', () => {
    expect(NATIVE_DEFAULTS).toEqual({
      encryptThumbnails: false,
      originalCacheCapBytes: 2_147_483_648,
      largeUploadsOnWifiOnly: true,
      largeUploadThresholdBytes: 104_857_600,
      locationForCaptures: false,
      autoLockMinutes: 5,
      ...SPEC_22_DEFAULTS,
    });
  });

  it('has the web defaults of spec 09 §4.2', () => {
    expect(WEB_DEFAULTS).toEqual({
      encryptThumbnails: true,
      originalCacheCapBytes: 1_073_741_824,
      largeUploadsOnWifiOnly: true,
      largeUploadThresholdBytes: 104_857_600,
      locationForCaptures: false,
      autoLockMinutes: 15,
      ...SPEC_22_DEFAULTS,
    });
  });
});

describe('spec 23 settings', () => {
  it('keeps stored gallery filters and badges from before favorites and fills the new keys', async () => {
    await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, {
      schemaVersion: 1,
      galleryFilters: { video: 'include', note: 'off', multiple: 'off', shared: 'off', pending: 'off', keptOffline: 'off' },
      galleryBadges: { video: false, photoCount: true, note: true, pending: true, keptOffline: false },
    });
    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });
    expect(settings.get().galleryFilters).toEqual({ ...NATIVE_DEFAULTS.galleryFilters, video: 'include', favorite: 'off' });
    expect(settings.get().galleryBadges).toEqual({ ...NATIVE_DEFAULTS.galleryBadges, video: false, favorite: true });
  });
});

describe('spec 22 settings', () => {
  it('fills the spec 22 fields with defaults for settings stored before spec 22', async () => {
    await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, {
      schemaVersion: 1,
      encryptThumbnails: false,
      originalCacheCapBytes: 0,
      largeUploadsOnWifiOnly: false,
      largeUploadThresholdBytes: 104_857_600,
      locationForCaptures: true,
      autoLockMinutes: 30,
    });
    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });
    expect(settings.get()).toEqual({
      ...NATIVE_DEFAULTS,
      encryptThumbnails: false,
      originalCacheCapBytes: 0,
      largeUploadsOnWifiOnly: false,
      locationForCaptures: true,
      autoLockMinutes: 30,
    });
  });

  it('rejects unknown choices and out-of-range columns', () => {
    const valid = { schemaVersion: 1, ...NATIVE_DEFAULTS };
    expect(deviceSettingsSchema.safeParse({ ...valid, appTheme: 'sepia' }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, galleryColumnsPortrait: 11 }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, galleryColumnsPortrait: 10 }).success).toBe(true);
    expect(deviceSettingsSchema.safeParse({ ...valid, galleryFilters: { ...valid.galleryFilters, video: 'maybe' } }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, gallerySort: { key: 'random', descending: false } }).success).toBe(true);
    expect(deviceSettingsSchema.safeParse({ ...valid, holdToRecordMaxSeconds: 7 }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, cameraApp: 'system' }).success).toBe(true);
  });
});

describe('deviceSettingsSchema', () => {
  const valid = { schemaVersion: 1, ...NATIVE_DEFAULTS };

  it('accepts the defaults', () => {
    expect(deviceSettingsSchema.safeParse(valid).success).toBe(true);
  });

  it.each([0, 61, 1.5, -5])('rejects an auto-lock of %s minutes', (autoLockMinutes) => {
    expect(deviceSettingsSchema.safeParse({ ...valid, autoLockMinutes }).success).toBe(false);
  });

  it.each([1, 60])('accepts an auto-lock of %i minutes', (autoLockMinutes) => {
    expect(deviceSettingsSchema.safeParse({ ...valid, autoLockMinutes }).success).toBe(true);
  });

  it('rejects an upload threshold below 1 MiB and accepts exactly 1 MiB', () => {
    expect(
      deviceSettingsSchema.safeParse({ ...valid, largeUploadThresholdBytes: MIN_THRESHOLD_BYTES - 1 })
        .success,
    ).toBe(false);
    expect(
      deviceSettingsSchema.safeParse({ ...valid, largeUploadThresholdBytes: MIN_THRESHOLD_BYTES })
        .success,
    ).toBe(true);
  });

  it('rejects a negative or fractional cache cap and accepts 0 for no limit', () => {
    expect(deviceSettingsSchema.safeParse({ ...valid, originalCacheCapBytes: -1 }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, originalCacheCapBytes: 1.5 }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, originalCacheCapBytes: 0 }).success).toBe(true);
  });

  it('rejects non-boolean switches', () => {
    expect(deviceSettingsSchema.safeParse({ ...valid, locationForCaptures: 'yes' }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, encryptThumbnails: 1 }).success).toBe(false);
    expect(deviceSettingsSchema.safeParse({ ...valid, largeUploadsOnWifiOnly: null }).success).toBe(false);
  });
});

describe('createDeviceSettings', () => {
  it('returns the platform defaults when nothing is stored', async () => {
    const settings = await createDeviceSettings({ rowStore, defaults: WEB_DEFAULTS });

    expect(settings.get()).toEqual(WEB_DEFAULTS);
  });

  it('merges an update, keeps the other fields and stores JSON with schemaVersion 1', async () => {
    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });

    await settings.update({ locationForCaptures: true, autoLockMinutes: 30 });

    expect(settings.get()).toEqual({ ...NATIVE_DEFAULTS, locationForCaptures: true, autoLockMinutes: 30 });
    expect(await storedJson()).toEqual({
      schemaVersion: 1,
      ...NATIVE_DEFAULTS,
      locationForCaptures: true,
      autoLockMinutes: 30,
    });
  });

  it('notifies subscribers after an update and stops after unsubscribe', async () => {
    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });
    const seen: DeviceSettingsValue[] = [];
    const unsubscribe = settings.subscribe(() => seen.push(settings.get()));

    await settings.update({ largeUploadsOnWifiOnly: false });
    unsubscribe();
    await settings.update({ largeUploadsOnWifiOnly: true });

    expect(seen).toHaveLength(1);
    expect(seen[0]?.largeUploadsOnWifiOnly).toBe(false);
  });

  it('loads what an earlier instance stored', async () => {
    const first = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });
    await first.update({ originalCacheCapBytes: 0, encryptThumbnails: true });

    const second = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });

    expect(second.get()).toEqual({ ...NATIVE_DEFAULTS, originalCacheCapBytes: 0, encryptThumbnails: true });
  });

  it('falls back to the defaults when the stored value is not readable', async () => {
    await rowStore.kvSet(STORAGE_KEYS.settingsDevice, Uint8Array.of(1, 2, 3));

    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });

    expect(settings.get()).toEqual(NATIVE_DEFAULTS);
  });

  it('replaces an invalid field by its default and keeps the valid ones', async () => {
    await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, {
      schemaVersion: 1,
      autoLockMinutes: 99,
      locationForCaptures: true,
      largeUploadThresholdBytes: 10,
    });

    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });

    expect(settings.get()).toEqual({ ...NATIVE_DEFAULTS, locationForCaptures: true });
  });

  it('is loaded after a successful read, with or without a stored value', async () => {
    const empty = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });
    expect(empty.loaded()).toBe(true);

    await empty.update({ autoLockMinutes: 10 });
    const stored = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });
    expect(stored.loaded()).toBe(true);
  });

  it('is not loaded after a read error and serves the defaults', async () => {
    const failing: RowStore = {
      ...rowStore,
      kvGetJson: async () => {
        throw new Error('disk gone');
      },
    };

    const settings = await createDeviceSettings({ rowStore: failing, defaults: NATIVE_DEFAULTS });

    expect(settings.loaded()).toBe(false);
    expect(settings.get()).toEqual(NATIVE_DEFAULTS);
  });

  it('uses the defaults and reports schemaVersion when the stored version is not 1', async () => {
    await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, { schemaVersion: 2, autoLockMinutes: 10 });
    const reported: (readonly string[])[] = [];

    const settings = await createDeviceSettings({
      rowStore,
      defaults: NATIVE_DEFAULTS,
      onInvalidFields: (fields) => reported.push(fields),
    });

    expect(settings.get()).toEqual(NATIVE_DEFAULTS);
    expect(reported.flat()).toContain('schemaVersion');
  });

  it('uses the defaults when the stored value has no schemaVersion', async () => {
    await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, { autoLockMinutes: 10 });

    const settings = await createDeviceSettings({ rowStore, defaults: NATIVE_DEFAULTS });

    expect(settings.get()).toEqual(NATIVE_DEFAULTS);
  });

  it('fills fields that are missing from an older stored value with the defaults', async () => {
    await rowStore.kvSetJson(STORAGE_KEYS.settingsDevice, { schemaVersion: 1, autoLockMinutes: 10 });

    const settings = await createDeviceSettings({ rowStore, defaults: WEB_DEFAULTS });

    expect(settings.get()).toEqual({ ...WEB_DEFAULTS, autoLockMinutes: 10 });
  });
});
