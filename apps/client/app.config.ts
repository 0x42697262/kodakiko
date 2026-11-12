import type { ExpoConfig } from 'expo/config';

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
    permissions: [],
    blockedPermissions: [
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
    ],
  },
  web: { output: 'single', bundler: 'metro' },
  plugins: [
    'expo-router',
    'react-native-libsodium',
    'expo-secure-store',
    'expo-sqlite',
    './plugins/with-android-hardening',
    './plugins/with-android-toolchain',
  ],
  experiments: { typedRoutes: true },
};

export default config;
