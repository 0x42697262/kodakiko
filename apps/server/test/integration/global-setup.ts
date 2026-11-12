import { execFile } from 'node:child_process';
import { chmod, writeFile } from 'node:fs/promises';
import { promisify } from 'node:util';
import pg from 'pg';
import {
  DEFAULT_TEST_DATABASE_URL,
  GARAGE_API_DEADLINE_MS,
  GARAGE_API_POLL_MS,
  GARAGE_BUCKET,
  GARAGE_CAPACITY,
  GARAGE_COMMAND_TIMEOUT_MS,
  GARAGE_COMPOSE_FILE,
  GARAGE_ENDPOINT,
  GARAGE_KEY_FILE,
  GARAGE_KEY_FILE_MODE,
  GARAGE_KEY_NAME,
  GARAGE_LAYOUT_VERSION,
  GARAGE_NO_ROLE_MARKER,
  GARAGE_REGION,
  GARAGE_SERVICE,
  GARAGE_ZONE,
  PROVIDER_ENV,
  PROVIDER_TEST_ORIGIN,
} from './test-constants.ts';

const CONNECT_DEADLINE_MS = 10_000;
const RETRY_DELAY_MS = 500;
const COMPOSE_COMMAND = 'docker compose -f infra/compose/docker-compose.test.yml up -d';
const COMPOSE_BASE = ['compose', '-f', GARAGE_COMPOSE_FILE];
const NODE_ID_PATTERN = /^([0-9a-f]{16})\s/m;
const MAX_BUFFER_BYTES = 1_048_576;

const run = promisify(execFile);

async function canConnect(url: string): Promise<boolean> {
  const client = new pg.Client({
    connectionString: url,
    connectionTimeoutMillis: RETRY_DELAY_MS * 4,
  });
  try {
    await client.connect();
    return true;
  } catch {
    return false;
  } finally {
    await client.end().catch(() => undefined);
  }
}

async function waitForDatabase(): Promise<void> {
  const url = process.env['TEST_DATABASE_URL'] ?? DEFAULT_TEST_DATABASE_URL;
  const deadline = Date.now() + CONNECT_DEADLINE_MS;
  while (Date.now() < deadline) {
    if (await canConnect(url)) return;
    await new Promise((resolve) => setTimeout(resolve, RETRY_DELAY_MS));
  }
  throw new Error(`Cannot connect to the test database. Start it with: ${COMPOSE_COMMAND}`);
}

async function garage(...args: readonly string[]): Promise<string> {
  const { stdout } = await run(
    'docker',
    [...COMPOSE_BASE, 'exec', '-T', GARAGE_SERVICE, '/garage', ...args],
    { timeout: GARAGE_COMMAND_TIMEOUT_MS, maxBuffer: MAX_BUFFER_BYTES },
  );
  return stdout;
}

async function garageSucceeds(...args: readonly string[]): Promise<boolean> {
  try {
    await garage(...args);
    return true;
  } catch {
    return false;
  }
}

async function startGarage(): Promise<void> {
  await run('docker', [...COMPOSE_BASE, 'up', '-d', '--wait', GARAGE_SERVICE], {
    timeout: GARAGE_COMMAND_TIMEOUT_MS,
  });
}

async function assignLayoutOnce(): Promise<void> {
  const status = await garage('status');
  if (!status.includes(GARAGE_NO_ROLE_MARKER)) return;
  const nodeId = NODE_ID_PATTERN.exec(status)?.[1];
  if (nodeId === undefined) throw new Error('Cannot read the Garage node id from `garage status`.');
  await garage('layout', 'assign', '-z', GARAGE_ZONE, '-c', GARAGE_CAPACITY, nodeId);
  await garage('layout', 'apply', '--version', GARAGE_LAYOUT_VERSION);
}

async function ensureBucketAndKey(): Promise<{ accessKeyId: string; secretAccessKey: string }> {
  if (!(await garageSucceeds('bucket', 'info', GARAGE_BUCKET))) {
    await garage('bucket', 'create', GARAGE_BUCKET);
  }
  if (!(await garageSucceeds('key', 'info', GARAGE_KEY_NAME))) {
    await garage('key', 'create', GARAGE_KEY_NAME);
  }
  await garage(
    'bucket',
    'allow',
    '--read',
    '--write',
    '--owner',
    GARAGE_BUCKET,
    '--key',
    GARAGE_KEY_NAME,
  );
  const accessKeyId = (await garage('key', 'info', '--key-id', GARAGE_KEY_NAME)).trim();
  const secretAccessKey = (await garage('key', 'info', '--secret-key', GARAGE_KEY_NAME)).trim();
  return { accessKeyId, secretAccessKey };
}

async function waitForS3Api(): Promise<void> {
  const deadline = Date.now() + GARAGE_API_DEADLINE_MS;
  while (Date.now() < deadline) {
    const answered = await fetch(GARAGE_ENDPOINT).then(
      () => true,
      () => false,
    );
    if (answered) return;
    await new Promise((resolve) => setTimeout(resolve, GARAGE_API_POLL_MS));
  }
  throw new Error('The test Garage S3 API did not answer in time.');
}

async function bootstrapGarage(): Promise<void> {
  await startGarage();
  await assignLayoutOnce();
  const key = await ensureBucketAndKey();
  await waitForS3Api();
  await writeFile(GARAGE_KEY_FILE, JSON.stringify(key), { mode: GARAGE_KEY_FILE_MODE });
  await chmod(GARAGE_KEY_FILE, GARAGE_KEY_FILE_MODE);
  process.env[PROVIDER_ENV.endpoint] = GARAGE_ENDPOINT;
  process.env[PROVIDER_ENV.publicEndpoint] = GARAGE_ENDPOINT;
  process.env[PROVIDER_ENV.region] = GARAGE_REGION;
  process.env[PROVIDER_ENV.bucket] = GARAGE_BUCKET;
  process.env[PROVIDER_ENV.accessKeyId] = key.accessKeyId;
  process.env[PROVIDER_ENV.secretAccessKey] = key.secretAccessKey;
  process.env[PROVIDER_ENV.forcePathStyle] = 'true';
  process.env[PROVIDER_ENV.origin] = PROVIDER_TEST_ORIGIN;
}

export default async function setup(): Promise<void> {
  await waitForDatabase();
  await bootstrapGarage();
}
