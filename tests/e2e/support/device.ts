import { randomBytes } from 'node:crypto';
import { createTestClientContext, type TestClientContext } from '@kodakiko/client/testing';
import { E2E_PASSWORD, USERNAME_PREFIX, USERNAME_RANDOM_BYTES } from './constants.ts';
import { createTestFetcher, type TestFetcher } from './fetchers.ts';
import type { Harness } from './harness.ts';

export type LogRecord = {
  readonly event: string;
  readonly fields: Readonly<Record<string, string | number | boolean | null>>;
};

export type Account = { readonly username: string; readonly password: string };

export type Device = {
  readonly name: string;
  readonly context: TestClientContext;
  readonly fetcher: TestFetcher;
  readonly account: Account;
  readonly userId: string;
  readonly logs: () => readonly LogRecord[];
};

const SYSTEM_CLOCK = {
  now: () => Date.now(),
  nowIso: () => new Date().toISOString(),
};

function newAccount(): Account {
  return {
    username: `${USERNAME_PREFIX}${randomBytes(USERNAME_RANDOM_BYTES).toString('hex')}`,
    password: E2E_PASSWORD,
  };
}

async function buildDevice(harness: Harness, name: string, account: Account): Promise<Device> {
  const fetcher = createTestFetcher();
  const records: LogRecord[] = [];
  const context = await createTestClientContext({
    baseUrl: harness.baseUrl,
    fetcher: fetcher.fetcher,
    sodium: harness.sodium,
    clock: SYSTEM_CLOCK,
    log: { warn: (event, fields) => void records.push({ event, fields }) },
  });
  return {
    name,
    context,
    fetcher,
    account,
    userId: '',
    logs: () => [...records],
  };
}

function withUserId(device: Device): Device {
  const state = device.context.accountState.get();
  if (state.kind !== 'unlocked') throw new Error(`${device.name} is not unlocked`);
  return { ...device, userId: state.userId };
}

export async function signUpDevice(harness: Harness, name: string): Promise<Device> {
  const account = newAccount();
  const device = await buildDevice(harness, name, account);
  await device.context.auth.signUp({
    username: account.username,
    password: account.password,
    inviteToken: null,
    keepSignedIn: false,
  });
  return withUserId(device);
}

export async function signInDevice(
  harness: Harness,
  name: string,
  account: Account,
): Promise<Device> {
  const device = await buildDevice(harness, name, account);
  await device.context.auth.signIn({
    username: account.username,
    password: account.password,
    keepSignedIn: false,
  });
  return withUserId(device);
}
