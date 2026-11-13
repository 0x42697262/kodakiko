import type { ExpoConfig } from 'expo/config';

const LOCATION_PERMISSION_TEXT =
  'Kodakiko adds your location to new photos only if you turn this on in Settings.';

const config: ExpoConfig = {
  name: 'Kodakiko',
  slug: 'kodakiko',
  scheme: 'kodakiko',
  version: '0.1.0',
  orientation: 'portrait',
  userInterfaceStyle: 'automatic',
  android: {
    package: 'com.x42697262.kodakiko',
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
  ],
  experiments: { typedRoutes: true },
};

export default config;
