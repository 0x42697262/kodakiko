import { fileURLToPath } from 'node:url';

export const STACK_PROJECT = 'kodakiko-ui';
export const COMPOSE_FILE = 'infra/compose/docker-compose.ui.yml';
export const SERVER_ENV_FILE = 'apps/server/.env.ui';
export const SERVER_MAIN = 'apps/server/dist/main.js';
export const SERVER_CLI = 'apps/server/dist/cli.js';
export const SERVER_PACKAGE = '@kodakiko/server';
export const CLIENT_PACKAGE = '@kodakiko/client';
export const CLIENT_BUILD_SCRIPT = 'build:web';
export const LINK_VIEWER_PACKAGE = '@kodakiko/link-viewer';
export const LINK_VIEWER_BUILD_SCRIPT = 'build';
export const LINK_VIEWER_CADDYFILE = './ui/link-viewer.Caddyfile';
export const LINK_VIEWER_WEB_ROOT = '../../apps/link-viewer/dist';
export const LINK_VIEWER_SPEC = 'U-18-link-viewer.spec.ts';
export const WORKSPACE_MARKER = 'pnpm-workspace.yaml';

export const WEB_ORIGIN = 'http://localhost:9580';
export const WEB_PORT = 9580;
export const S3_PORT = 9581;
export const API_PORT = 9582;
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
  appId: 'UI_APP_ID',
  javaHome: 'JAVA_HOME',
  publicBaseUrl: 'PUBLIC_BASE_URL',
  s3PublicEndpoint: 'S3_PUBLIC_ENDPOINT',
  uiCaddyfile: 'UI_CADDYFILE',
  uiWebRoot: 'UI_WEB_ROOT',
});
export const FLAG_ON = '1';

export const ANDROID_HOST_ALIAS = '10.0.2.2';
export const ANDROID_WEB_ORIGIN = `http://${ANDROID_HOST_ALIAS}:${WEB_PORT}`;
export const ANDROID_S3_ORIGIN = `http://${ANDROID_HOST_ALIAS}:${S3_PORT}`;
export const ANDROID_API_ENV_OVERRIDES: Readonly<Record<string, string>> = Object.freeze({
  [ENV_NAMES.publicBaseUrl]: ANDROID_WEB_ORIGIN,
  [ENV_NAMES.s3PublicEndpoint]: ANDROID_S3_ORIGIN,
});
export const HOST_LOOPBACK_ADDRESS = '127.0.0.1';
export const HTTP_DEFAULT_PORT = 80;
export const HTTP_DEFAULT_METHOD = 'GET';
export const HTTP_HOST_HEADER = 'host';
export const HTTP_CONTENT_LENGTH_HEADER = 'content-length';
export const HTTP_HEADER_LIST_SEPARATOR = ', ';
export const HTTP_BAD_GATEWAY = 502;
export const HTTP_NULL_BODY_STATUSES: readonly number[] = [204, 205, 304];
export const CADDY_SERVICE = 'caddy-ui';
export const CADDY_STOP = 'stop';
export const CADDY_START = 'start';
export const CADDY_ACTIONS = [CADDY_STOP, CADDY_START] as const;

export const REPORTS_DIRECTORY = 'tests/ui/reports';
export const SERVER_LOG_FILE = 'server.log';
export const ANDROID_REPORTS_DIRECTORY = 'android';

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
export const FIXTURES_MEDIA_DIRECTORY = 'tests/fixtures/media';
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

export const FIXTURE_NAMES = [
  'photo-gps.jpg',
  'photo-portrait.jpg',
  'screenshot.png',
  'video-20s.mp4',
  'video-gps.mov',
  'video-short.webm',
] as const;
export type FixtureName = (typeof FIXTURE_NAMES)[number];
export const SEED_SETTLE_DEADLINE_MS = 120_000;
export const SEED_SETTLE_POLL_MS = 200;
export const SYNC_STATE_PATH = '/api/v1/sync/state';
export const COLLECTIONS_PATH = '/api/v1/collections';
export const DIFF_PATH_SUFFIX = '/diff';
export const DIFF_PAGE_LIMIT = 500;
export const SEED_TIMEOUT_MS = 180_000;

export const FLOW_ID_DASH = '-';
export const FLOW_FILE_PATTERN = /^(U-\d\d)\.flow\.ts$/;

export const LEGACY_APP_ID = 'com.x42697262.kodakiko';
export const APP_ID = 'com.x42697262.kodakiko.next';
export const APP_ID_VARIABLE = 'APP_ID';
export const MAESTRO_VERSION = '2.12.1';
export const JAVA_MIN_MAJOR = 21;
export const ANDROID_EMULATOR_HINT = 'Start the emulator: `emulator -avd kodakiko-ui`';
export const ANDROID_BUNDLE_FILE = 'android/dist/run.mjs';
export const ANDROID_FLOWS_SOURCE_DIRECTORY = 'tests/ui/android/flows';
export const MAESTRO_JUNIT_FORMAT = 'junit';
export const MAESTRO_OUTPUT_SUFFIX = '.output';
export const ANDROID_PROJECT_DIRECTORY = 'apps/android';
export const ANDROID_DEBUG_APK = 'apps/android/app/build/outputs/apk/debug/app-debug.apk';
export const NICE_COMMAND = 'nice';
export const NICE_ARGUMENTS: readonly string[] = ['-n', '19'];
export const GRADLE_COMMAND = './gradlew';
export const GRADLE_BUILD_ARGUMENTS: readonly string[] = [
  '--offline',
  '--no-daemon',
  ':app:assembleDebug',
];
export const ADB_INSTALL_ARGUMENTS: readonly string[] = ['install', '-r', '-t'];

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
export const JAVA_COMMAND = 'java';
export const JAVA_HOME_BINARY = 'bin/java';
export const JAVA_VERSION_PATTERN = /version "(\d+)/;
export const SIDECAR_SUFFIX = '.flow.ts';
export const DEFAULT_SERVER_INPUT_NAME = 'SERVER_INPUT';
export const ANDROID_PROJECT_NAME = 'android';
export const MAESTRO_RELATIVE_BINARY = 'maestro/bin/maestro';
export const MAESTRO_HOME_DIRECTORY = '.maestro-kodakiko';

export const LINK_SEED_REQUEST_TIMEOUT_MS = 60_000;
export const LINK_SEED_DEVICE_LABEL = 'UI link seeder';
export const LINK_SEED_HOME_COLLECTION = 'Journal';
export const LINK_SEED_ID_BYTES = 16;
export const LINK_SEED_KEY_BYTES = 32;
export const LINK_SEED_SCHEMA_VERSION = 1;
export const LINK_SEED_OBJECT_CONTENT_TYPE = 'application/octet-stream';
export const LINK_SEED_THUMBNAIL_MIME = 'image/webp';
export const LINK_SEED_THUMBNAIL_WEBP_BASE64 = 'UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==';
export const LINK_SEED_THUMBNAIL_SIZE_PX = 1;
export const LINK_PATH_PREFIX = '/s/';
