import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import config from '../app.config';

const CAPTURE_PACKAGES = [
  'react-native-vision-camera',
  'react-native-nitro-modules',
  'react-native-nitro-image',
  'expo-image-picker',
  'expo-image-manipulator',
  'expo-video',
  'expo-video-thumbnails',
  'expo-location',
] as const;

const LOCATION_PERMISSION_TEXT =
  'Kodakiko adds your location to new photos only if you turn this on in Settings.';

function pluginName(plugin: unknown): string {
  return Array.isArray(plugin) ? String(plugin[0]) : String(plugin);
}

describe('capture app config', () => {
  it('requests camera and microphone permissions', () => {
    expect(config.android?.permissions).toEqual(
      expect.arrayContaining(['android.permission.CAMERA', 'android.permission.RECORD_AUDIO']),
    );
  });

  it('keeps the storage permissions blocked', () => {
    expect(config.android?.blockedPermissions).toEqual([
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
    ]);
  });

  it('configures expo-location for foreground use only', () => {
    const entry = (config.plugins ?? []).find((plugin) => pluginName(plugin) === 'expo-location');

    expect(entry).toEqual([
      'expo-location',
      {
        locationWhenInUsePermission: LOCATION_PERMISSION_TEXT,
        isAndroidBackgroundLocationEnabled: false,
      },
    ]);
  });

  it('does not register the expo-image-picker plugin, which would block CAMERA and RECORD_AUDIO', () => {
    const names = (config.plugins ?? []).map(pluginName);

    expect(names).not.toContain('expo-image-picker');
  });
});

describe('capture packages', () => {
  it('are client dependencies taken from the catalog', () => {
    const manifest = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as {
      dependencies: Record<string, string>;
    };

    for (const name of CAPTURE_PACKAGES) {
      expect(manifest.dependencies[name], name).toBe('catalog:');
    }
  });
});

describe('native feel app config (spec 24 phase 4)', () => {
  it('turns on the Android predictive back gesture', () => {
    expect(config.android?.predictiveBackGestureEnabled).toBe(true);
  });
});
