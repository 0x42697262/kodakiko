export {
  DEFAULT_TEST_DATABASE_URL,
  GARAGE_BUCKET,
  GARAGE_ENDPOINT,
  GARAGE_KEY_FILE,
} from '@kodakiko/server/testing';

export const ADMIN_DATABASE_ENV = 'TEST_DATABASE_URL';
export const DATABASE_NAME_PREFIX = 'kodakiko_e2e_';
export const DATABASE_NAME_RANDOM_BYTES = 8;
export const DATABASE_POOL_MAX = 5;
export const DATABASE_STATEMENT_TIMEOUT_MS = 10_000;
export const DATABASE_CONNECT_TIMEOUT_MS = 5000;

export const LISTEN_HOST = '127.0.0.1';
export const LISTEN_PORT = 0;

export const E2E_PASSWORD = 'correct horse battery staple';
export const USERNAME_PREFIX = 'e2e-';
export const USERNAME_RANDOM_BYTES = 4;

export const E2E_ENV: Readonly<Record<string, string>> = Object.freeze({
  FAKE_PARAMS_SECRET: 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU',
  PUBLIC_BASE_URL: 'http://localhost:8080',
  REQUIRE_HTTPS: 'false',
  RATE_LIMITS_ENABLED: 'false',
  SIGNUP_MODE: 'open',
  LOG_LEVEL: 'fatal',
  JOBS_ENABLED: 'false',
  STORAGE_PROVIDER: 'garage',
});

export const KIBIBYTE = 1024;
export const MEBIBYTE = 1_048_576;
export const MULTIPART_MEDIA_BYTES = 10 * MEBIBYTE;
export const SMALL_MEDIA_BYTES = 2 * KIBIBYTE;
export const DAY_MS = 86_400_000;

export const SETTLE_DEADLINE_MS = 90_000;
export const SETTLE_POLL_MS = 150;

export const TINY_QUOTA_BYTES = 1;
export const QUOTA_EXCEEDED_CODE = 'QUOTA_EXCEEDED';
export const ROLLBACK_REJECTED_EVENT = 'sync.rollback_rejected';
export const DIFF_PATH_FRAGMENT = '/diff?since=';
export const PATCH_METHOD = 'PATCH';
export const DROPPED_RESPONSE_MESSAGE = 'response dropped by the e2e fetcher';

export const EARLIER_EDIT_UTC = '2030-01-01T10:00:00.000Z';
export const LATER_EDIT_UTC = '2030-01-01T11:00:00.000Z';
export const FIRST_REVISION = 1;
export const SECOND_REVISION = 2;
export const THIRD_REVISION = 3;
export const LOST_RESPONSE_COUNT = 3;
