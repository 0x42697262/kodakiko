import type { ExpoConfig } from 'expo/config';

const LOCATION_PERMISSION_TEXT =
  'Kodakiko adds your location to new photos only if you turn this on in Settings.';

const APP_VERSION = '0.1.1';
const VERSION_PART_RANGE = 100;

function versionCodeOf(version: string): number {
  return version.split('.').reduce((code, part) => code * VERSION_PART_RANGE + Number(part), 0);
}

const config: ExpoConfig = {
  name: 'Kodakiko',
  slug: 'kodakiko',
  scheme: 'kodakiko',
  version: APP_VERSION,
  orientation: 'portrait',
  userInterfaceStyle: 'automatic',
  android: {
    package: 'com.x42697262.kodakiko',
    versionCode: versionCodeOf(APP_VERSION),
    allowBackup: false,
    permissions: ['android.permission.CAMERA', 'android.permission.RECORD_AUDIO'],
    blockedPermissions: [
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
    ],
  },
  web: { output: 'single', bundler: 'metro', favicon: './assets/icon-192.png' },
  plugins: [
    'expo-router',
    'react-native-libsodium',
    'expo-secure-store',
    'expo-sqlite',
    [
      'expo-location',
      {
        locationWhenInUsePermission: LOCATION_PERMISSION_TEXT,
        isAndroidBackgroundLocationEnabled: false,
      },
    ],
    './plugins/with-android-hardening',
    './plugins/with-android-toolchain',
    './plugins/with-system-bars',
  ],
  experiments: { typedRoutes: true },
};

export default config;
