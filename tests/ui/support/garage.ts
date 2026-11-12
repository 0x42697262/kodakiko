import {
  COMPOSE_FILE,
  GARAGE_BUCKET,
  GARAGE_CAPACITY,
  GARAGE_COMMAND_TIMEOUT_MS,
  GARAGE_KEY_NAME,
  GARAGE_LAYOUT_VERSION,
  GARAGE_NODE_ID_PATTERN,
  GARAGE_NO_ROLE_MARKER,
  GARAGE_SERVICE,
  GARAGE_STATUS_DEADLINE_MS,
  GARAGE_STATUS_POLL_MS,
  GARAGE_ZONE,
  S3_PORT,
  STACK_PROJECT,
  READY_POLL_MS,
} from './constants.ts';
import { fromRoot } from './paths.ts';
import { run } from './process.ts';
import { STACK_TEXT } from './text.ts';

export type GarageKey = { readonly accessKeyId: string; readonly secretAccessKey: string };

const S3_ROOT_URL = `http://127.0.0.1:${S3_PORT}`;

function pause(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function garage(...args: readonly string[]): Promise<string> {
  const { stdout } = await run(
    'docker',
    [
      'compose',
      '-p',
      STACK_PROJECT,
      '-f',
      COMPOSE_FILE,
      'exec',
      '-T',
      GARAGE_SERVICE,
      '/garage',
      ...args,
    ],
    { cwd: fromRoot(), timeoutMs: GARAGE_COMMAND_TIMEOUT_MS },
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

async function waitForStatus(): Promise<string> {
  const deadline = Date.now() + GARAGE_STATUS_DEADLINE_MS;
  while (Date.now() < deadline) {
    try {
      return await garage('status');
    } catch {
      await pause(GARAGE_STATUS_POLL_MS);
    }
  }
  throw new Error(STACK_TEXT.garageStatusTimeout);
}

async function assignLayout(status: string): Promise<void> {
  if (!status.includes(GARAGE_NO_ROLE_MARKER)) return;
  const nodeId = GARAGE_NODE_ID_PATTERN.exec(status)?.[1];
  if (nodeId === undefined) throw new Error(STACK_TEXT.garageNodeIdMissing);
  await garage('layout', 'assign', '-z', GARAGE_ZONE, '-c', GARAGE_CAPACITY, nodeId);
  await garage('layout', 'apply', '--version', GARAGE_LAYOUT_VERSION);
}

async function ensureBucketAndKey(): Promise<GarageKey> {
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
  const deadline = Date.now() + GARAGE_STATUS_DEADLINE_MS;
  while (Date.now() < deadline) {
    const answered = await fetch(S3_ROOT_URL).then(
      () => true,
      () => false,
    );
    if (answered) return;
    await pause(READY_POLL_MS);
  }
  throw new Error(STACK_TEXT.garageS3Timeout);
}

export async function bootstrapGarage(): Promise<GarageKey> {
  await assignLayout(await waitForStatus());
  const key = await ensureBucketAndKey();
  await waitForS3Api();
  return key;
}
