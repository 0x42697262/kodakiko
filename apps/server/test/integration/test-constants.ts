import { fileURLToPath } from 'node:url';

export const DEFAULT_TEST_DATABASE_URL = 'postgres://postgres:test@127.0.0.1:55432/kodakiko_test';

export const GARAGE_SERVICE = 'garage-test';
export const GARAGE_ENDPOINT = 'http://127.0.0.1:53900';
export const GARAGE_REGION = 'garage';
export const GARAGE_BUCKET = 'kodakiko-test';
export const GARAGE_KEY_NAME = 'test';
export const GARAGE_ZONE = 'dc1';
export const GARAGE_CAPACITY = '1G';
export const GARAGE_LAYOUT_VERSION = '1';
export const GARAGE_NO_ROLE_MARKER = 'NO ROLE ASSIGNED';
export const GARAGE_COMPOSE_FILE = fileURLToPath(
  new URL('../../../../infra/compose/docker-compose.test.yml', import.meta.url),
);
export const GARAGE_KEY_FILE = fileURLToPath(new URL('../.garage-test-key.json', import.meta.url));
export const GARAGE_KEY_FILE_MODE = 0o600;
export const GARAGE_API_DEADLINE_MS = 30_000;
export const GARAGE_API_POLL_MS = 250;
export const GARAGE_COMMAND_TIMEOUT_MS = 60_000;

export const PROVIDER_ENV = Object.freeze({
  endpoint: 'PROVIDER_TEST_ENDPOINT',
  publicEndpoint: 'PROVIDER_TEST_PUBLIC_ENDPOINT',
  region: 'PROVIDER_TEST_REGION',
  bucket: 'PROVIDER_TEST_BUCKET',
  accessKeyId: 'PROVIDER_TEST_ACCESS_KEY_ID',
  secretAccessKey: 'PROVIDER_TEST_SECRET_ACCESS_KEY',
  forcePathStyle: 'PROVIDER_TEST_FORCE_PATH_STYLE',
  origin: 'PROVIDER_TEST_ORIGIN',
});
export const PROVIDER_TEST_ORIGIN = 'https://kodakiko.example.com';
