import { fileURLToPath } from 'node:url';

export const STACK_PROJECT = 'kodakiko-ui';
export const COMPOSE_FILE = 'infra/compose/docker-compose.ui.yml';
export const SERVER_ENV_FILE = 'apps/server/.env.ui';
export const SERVER_MAIN = 'apps/server/dist/main.js';
export const SERVER_CLI = 'apps/server/dist/cli.js';
export const SERVER_PACKAGE = '@kodakiko/server';
export const CLIENT_PACKAGE = '@kodakiko/client';
export const CLIENT_BUILD_SCRIPT = 'build:web';
export const CLIENT_PREBUILD_SCRIPT = 'prebuild:android';
export const CLIENT_METRO_SCRIPT = 'start:metro-ui';
export const CLIENT_ANDROID_DIRECTORY = 'apps/client/android';
export const WORKSPACE_MARKER = 'pnpm-workspace.yaml';

export const WEB_ORIGIN = 'http://localhost:9580';
export const WEB_PORT = 9580;
export const S3_PORT = 9581;
export const API_PORT = 9582;
export const METRO_PORT = 8081;
export const METRO_STATUS_URL = `http://localhost:${METRO_PORT}/status`;
export const METRO_RUNNING_STATUS = 'packager-status:running';
export const METRO_READY_MS = 180_000;
export const READY_URL = `${WEB_ORIGIN}/health/ready`;
export const UI_STACK_READY_MS = 120_000;
export const READY_POLL_MS = 500;
export const READY_REQUEST_TIMEOUT_MS = 5_000;
export const HOST_FIREWALL_HINT =
  'The API must be reachable from the Caddy container through host.docker.internal:9582; allow that port in the host firewall.';

export const GARAGE_SERVICE = 'garage-ui';
export const GARAGE_BUCKET = 'kodakiko-ui';
export const GARAGE_KEY_NAME = 'ui';
export const GARAGE_ZONE = 'dc1';
export const GARAGE_CAPACITY = '1G';
export const GARAGE_LAYOUT_VERSION = '1';
export const GARAGE_NO_ROLE_MARKER = 'NO ROLE ASSIGNED';
export const GARAGE_NODE_ID_PATTERN = /^([0-9a-f]{16})\s/m;
export const GARAGE_STATUS_DEADLINE_MS = 30_000;
export const GARAGE_STATUS_POLL_MS = 1_000;
export const GARAGE_COMMAND_TIMEOUT_MS = 60_000;

export const COMMAND_MAX_BUFFER_BYTES = 8_388_608;
export const COMPOSE_UP_TIMEOUT_MS = 600_000;
export const BUILD_TIMEOUT_MS = 900_000;
export const KILL_GRACE_MS = 10_000;

export const ENV_NAMES = Object.freeze({
  s3AccessKeyId: 'UI_S3_ACCESS_KEY_ID',
  s3SecretAccessKey: 'UI_S3_SECRET_ACCESS_KEY',
  serverS3AccessKeyId: 'S3_ACCESS_KEY_ID',
  serverS3SecretAccessKey: 'S3_SECRET_ACCESS_KEY',
  skipBuild: 'UI_SKIP_BUILD',
  uiTestMode: 'EXPO_PUBLIC_UI_TEST',
  nonInteractive: 'CI',
  publicBaseUrl: 'PUBLIC_BASE_URL',
  s3PublicEndpoint: 'S3_PUBLIC_ENDPOINT',
});
export const FLAG_ON = '1';

export const ANDROID_HOST_ALIAS = '10.0.2.2';
export const ANDROID_WEB_ORIGIN = `http://${ANDROID_HOST_ALIAS}:${WEB_PORT}`;
export const ANDROID_S3_ORIGIN = `http://${ANDROID_HOST_ALIAS}:${S3_PORT}`;
export const ANDROID_API_ENV_OVERRIDES: Readonly<Record<string, string>> = Object.freeze({
  [ENV_NAMES.publicBaseUrl]: ANDROID_WEB_ORIGIN,
  [ENV_NAMES.s3PublicEndpoint]: ANDROID_S3_ORIGIN,
});
export const CADDY_SERVICE = 'caddy-ui';
export const CADDY_STOP = 'stop';
export const CADDY_START = 'start';
export const CADDY_ACTIONS = [CADDY_STOP, CADDY_START] as const;

export const REPORTS_DIRECTORY = 'tests/ui/reports';
export const SERVER_LOG_FILE = 'server.log';
export const ANDROID_REPORTS_DIRECTORY = 'android';
export const METRO_LOG_FILE = 'metro.log';

export const UI_TEST_PASSWORD = 'correct horse battery staple';
export const UI_TEST_NEW_PASSWORD = 'a brand new passphrase here';
export const WRONG_TEST_PASSWORD = 'definitely not the password';
export const USERS_ME_DELETE_PATH = '/api/v1/users/me/delete';
export const BEARER_PREFIX = 'Bearer ';
export const HTTP_ACCEPTED = 202;
export const INVITE_ROLES = ['member', 'admin'] as const;
export const INVITE_EXPIRES_DAYS = '1';
export const INVITE_URL_PATTERN = /https?:\/\/\S+/;
export const INVITE_FRAGMENT_PREFIX = '#';

export const FIXTURES_DIRECTORY = fileURLToPath(new URL('../../fixtures/media/', import.meta.url));
export const FIXTURES_SUMS_FILE = 'SHA256SUMS';
export const FIXTURES_MAX_TOTAL_BYTES = 10 * 1024 * 1024;
export const GENERATED_FIXTURES: readonly string[] = [
  'photo-gps-base.jpg',
  'photo-portrait.jpg',
  'screenshot.png',
  'video-20s.mp4',
  'video-gps.mov',
  'video-short.webm',
];

export const FLOW_ID_DASH = '-';
export const FLOW_FILE_PATTERN = /^(U-\d\d)\.flow\.ts$/;

export const APP_ID = 'com.x42697262.kodakiko';
export const MAESTRO_VERSION = '2.12.1';
export const JAVA_MIN_MAJOR = 17;
export const ANDROID_EMULATOR_HINT = 'Start the emulator: `emulator -avd kodakiko-ui`';
export const ANDROID_BUNDLE_FILE = 'android/dist/run.mjs';
export const ANDROID_FLOWS_SOURCE_DIRECTORY = 'tests/ui/android/flows';
export const MAESTRO_JUNIT_FORMAT = 'junit';
export const GRADLE_COMMAND = './gradlew';
export const GRADLE_INSTALL_TASK = 'installDebug';

export const FIXTURE_MIME_TYPES: Readonly<Record<string, string>> = Object.freeze({
  '.jpg': 'image/jpeg',
  '.png': 'image/png',
  '.mp4': 'video/mp4',
  '.mov': 'video/quicktime',
  '.webm': 'video/webm',
});
export const CSP_CONSOLE_MARKER = 'Content Security Policy';
export const RECOVERY_WORD_COUNT = 24;
export const RECOVERY_CONFIRM_COUNT = 3;
export const WEB_EXPECT_TIMEOUT_MS = 30_000;
export const WEB_TEST_TIMEOUT_MS = 120_000;
export const SODIUM_READY_TIMEOUT_MS = 30_000;
export const SIGN_UP_WORDS_TIMEOUT_MS = 60_000;
export const WEB_REPORT_DIRECTORY = 'web';
export const WEB_RESULTS_DIRECTORY = 'web-results';

export const ADB_COMMAND = 'adb';
export const APP_SETTLE_MS = 2_000;
export const ADB_TIMEOUT_MS = 60_000;
export const ADB_DEVICE_STATE = 'device';
export const ADB_DEVICES_HEADER = 'List of devices attached';
export const MAESTRO_TIMEOUT_MS = 1_800_000;
export const GRADLE_TIMEOUT_MS = 1_800_000;
export const JAVA_VERSION_PATTERN = /version "(\d+)/;
export const SIDECAR_SUFFIX = '.flow.ts';
export const DEFAULT_SERVER_INPUT_NAME = 'SERVER_INPUT';
export const METRO_STOP_SIGNAL = 'SIGTERM';
export const ANDROID_PROJECT_NAME = 'android';
export const MAESTRO_RELATIVE_BINARY = 'maestro/bin/maestro';
export const MAESTRO_HOME_DIRECTORY = '.maestro-kodakiko';
