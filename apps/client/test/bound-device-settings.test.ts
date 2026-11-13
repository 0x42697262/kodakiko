import { beforeEach, describe, expect, it } from 'vitest';
import { DEVICE_SETTINGS_DEFAULTS } from '../src/constants/device-settings-defaults.native';
import type { RowStore } from '../src/adapters/rowstore/rowstore';
import { createBoundDeviceSettings } from '../src/services/settings/bound-device-settings';
import { createMemoryRowStore } from './support';

let rowStore: RowStore;

beforeEach(async () => {
  rowStore = createMemoryRowStore();
  await rowStore.open('host/user');
});

describe('bound device settings', () => {
  it('returns the defaults before anything is loaded', () => {
    const bound = createBoundDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS });

    expect(bound.settings.get()).toEqual(DEVICE_SETTINGS_DEFAULTS);
  });

  it('reports loaded only between load and unload', async () => {
    const bound = createBoundDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS });
    expect(bound.settings.loaded()).toBe(false);

    await bound.load();
    expect(bound.settings.loaded()).toBe(true);

    bound.unload();
    expect(bound.settings.loaded()).toBe(false);
  });

  it('refuses an update before it is loaded', async () => {
    const bound = createBoundDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS });

    await expect(bound.settings.update({ autoLockMinutes: 10 })).rejects.toThrow();
  });

  it('reads and writes the stored settings once loaded and notifies subscribers', async () => {
    const bound = createBoundDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS });
    let notifications = 0;
    bound.settings.subscribe(() => {
      notifications += 1;
    });

    await bound.load();
    await bound.settings.update({ autoLockMinutes: 10 });

    expect(bound.settings.get().autoLockMinutes).toBe(10);
    expect(notifications).toBeGreaterThanOrEqual(2);
  });

  it('goes back to the defaults after unload', async () => {
    const bound = createBoundDeviceSettings({ rowStore, defaults: DEVICE_SETTINGS_DEFAULTS });
    await bound.load();
    await bound.settings.update({ autoLockMinutes: 10 });

    bound.unload();

    expect(bound.settings.get()).toEqual(DEVICE_SETTINGS_DEFAULTS);
  });
});
