import type { FastifyInstance } from 'fastify';
import {
  bytesSchema,
  decodeBytes,
  encodeBytes,
  errorResponseSchema,
  signUpResponse,
  type ErrorCode,
} from '@kodakiko/contract';
import {
  createSignUpMaterial,
  type KdfParams,
  type SignUpMaterial,
  type Sodium,
} from '@kodakiko/core';
import { createNodeSodium } from '../../../../packages/core/test/sodium-node.ts';
import { createFailedSignInTracker } from '../../src/adapters/memory/failed-sign-in-tracker.ts';
import type { Database } from '../../src/adapters/db/schema.ts';
import { buildApp } from '../../src/app.ts';
import type { Settings } from '../../src/config/settings.ts';
import type { UserRole } from '../../src/domain/enums.ts';
import { randomId } from '../../src/platform/ids.ts';
import type { Clock } from '../../src/platform/clock.ts';
import { silentLogger } from '../../src/platform/silent-logger.ts';
import { createAuthService, type AuthService } from '../../src/services/auth-service.ts';
import { createPlaceholderObjectStore, testSettings, type TestDatabase } from './helpers.ts';
import type { Kysely } from 'kysely';

export const FAST_OPS = 2;
export const FAST_MEM_BYTES = 8_388_608;
export const FLOOR_OPS = 32;
export const FLOOR_MEM_BYTES = 134_217_728;
export const DEFAULT_PASSWORD = 'correct horse battery staple';
export const DEFAULT_DEVICE_LABEL = 'Android';
const MINUTE_MS = 60_000;
const INVITE_FRAGMENT_SEPARATOR = '#';
const DEFAULT_INVITE_DAYS = 7;
const TRACKER_MAX_ENTRIES = 100;
const LOG_LEVEL_INFO = 'info';
const AUTH_BEARER = 'Bearer ';

export type MutableClock = Clock & {
  set(date: Date): void;
  advance(milliseconds: number): void;
};

export function createMutableClock(start: Date = new Date()): MutableClock {
  let current = start.getTime();
  return {
    now: () => new Date(current),
    set: (date) => {
      current = date.getTime();
    },
    advance: (milliseconds) => {
      current += milliseconds;
    },
  };
}

export type LogCapture = {
  readonly stream: { write(chunk: string): void };
  text(): string;
};

export function createLogCapture(): LogCapture {
  const chunks: string[] = [];
  return {
    stream: {
      write(chunk: string) {
        chunks.push(chunk);
      },
    },
    text: () => chunks.join(''),
  };
}

export type AuthContext = {
  readonly app: FastifyInstance;
  readonly db: Kysely<Database>;
  readonly clock: MutableClock;
  readonly settings: Settings;
  readonly sodium: Sodium;
  readonly logs: LogCapture;
  readonly inviteService: AuthService;
  close(): Promise<void>;
};

export type AuthContextOptions = {
  readonly env?: Readonly<Record<string, string>>;
  readonly inviteClock?: Clock;
};

export async function createAuthContext(
  testDatabase: TestDatabase,
  options: AuthContextOptions = {},
): Promise<AuthContext> {
  const settings = testSettings({
    RATE_LIMITS_ENABLED: 'false',
    LOG_LEVEL: LOG_LEVEL_INFO,
    ...options.env,
  });
  const clock = createMutableClock();
  const logs = createLogCapture();
  const app = await buildApp({
    settings,
    db: testDatabase.db,
    clock,
    readinessChecks: [],
    objectStore: createPlaceholderObjectStore(),
    logStream: logs.stream,
  });
  await app.ready();
  const inviteService = createAuthService({
    db: testDatabase.db,
    settings,
    clock: options.inviteClock ?? clock,
    failedSignIns: createFailedSignInTracker({ windowMs: MINUTE_MS, maxEntries: TRACKER_MAX_ENTRIES }),
    logger: silentLogger,
    checkOpenSignUpRate: async () => true,
  });
  return {
    app,
    db: testDatabase.db,
    clock,
    settings,
    sodium: await createNodeSodium(),
    logs,
    inviteService,
    close: () => app.close(),
  };
}

export async function createInviteToken(
  context: AuthContext,
  role: UserRole,
  expiresInDays: number = DEFAULT_INVITE_DAYS,
  service: AuthService = context.inviteService,
): Promise<string> {
  const { inviteUrl } = await service.createInvite(role, expiresInDays, null);
  return inviteUrl.split(INVITE_FRAGMENT_SEPARATOR)[1] ?? '';
}

export type CallResult = {
  readonly status: number;
  readonly json: unknown;
  readonly raw: string;
};

export async function call(
  app: FastifyInstance,
  method: 'GET' | 'POST',
  url: string,
  options: { readonly body?: unknown; readonly token?: string } = {},
): Promise<CallResult> {
  const response = await app.inject({
    method,
    url,
    ...(options.token === undefined
      ? {}
      : { headers: { authorization: `${AUTH_BEARER}${options.token}` } }),
    ...(options.body === undefined ? {} : { payload: options.body as object }),
  });
  return {
    status: response.statusCode,
    json: response.body === '' ? null : (response.json() as unknown),
    raw: response.body,
  };
}

export function errorCodeOf(result: CallResult): ErrorCode {
  return errorResponseSchema.parse(result.json).error.code;
}

export function bytesOf(value: unknown): Uint8Array {
  return decodeBytes(bytesSchema.parse(value));
}

export function fastParams(salt: Uint8Array): KdfParams {
  return { salt, opsLimit: FAST_OPS, memLimitBytes: FAST_MEM_BYTES };
}

export function signUpBody(
  material: SignUpMaterial,
  options: {
    readonly inviteToken: string | null;
    readonly deviceLabel?: string;
    readonly floor?: boolean;
  },
): Record<string, unknown> {
  const { request } = material;
  const floor = options.floor ?? true;
  return {
    userId: request.userId,
    username: request.username,
    inviteToken: options.inviteToken,
    deviceLabel: options.deviceLabel ?? DEFAULT_DEVICE_LABEL,
    salt: encodeBytes(request.salt),
    opsLimit: floor ? FLOOR_OPS : request.opsLimit,
    memLimitBytes: floor ? FLOOR_MEM_BYTES : request.memLimitBytes,
    loginKey: encodeBytes(request.loginKey),
    recoveryLoginKey: encodeBytes(request.recoveryLoginKey),
    publicKey: encodeBytes(request.publicKey),
    masterKeyByKek: encodeBytes(request.masterKeyByKek),
    masterKeyByRecovery: encodeBytes(request.masterKeyByRecovery),
    recoveryKeyByMaster: encodeBytes(request.recoveryKeyByMaster),
    privateKeyByMaster: encodeBytes(request.privateKeyByMaster),
    homeCollection: {
      id: request.homeCollection.id,
      keyByOwner: encodeBytes(request.homeCollection.keyByOwner),
      metadataBlob: encodeBytes(request.homeCollection.metadataBlob),
    },
  };
}

export type TestAccount = {
  readonly response: CallResult;
  readonly username: string;
  readonly password: string;
  readonly material: SignUpMaterial;
  readonly kdf: KdfParams;
  readonly token: string | null;
};

export async function createTestAccount(
  context: AuthContext,
  options: {
    readonly inviteToken: string | null;
    readonly username: string;
    readonly password?: string;
    readonly randomId?: () => string;
    readonly floor?: boolean;
  },
): Promise<TestAccount> {
  const password = options.password ?? DEFAULT_PASSWORD;
  const material = await createSignUpMaterial(
    context.sodium,
    {
      username: options.username,
      password,
      homeCollectionName: 'Journal',
      createdAtUtc: context.clock.now().toISOString(),
      randomId: options.randomId ?? randomId,
    },
    fastParams,
  );
  const response = await call(context.app, 'POST', '/api/v1/auth/sign-up', {
    body: signUpBody(material, {
      inviteToken: options.inviteToken,
      ...(options.floor === undefined ? {} : { floor: options.floor }),
    }),
  });
  const parsed = signUpResponse.safeParse(response.json);
  return {
    response,
    username: options.username,
    password,
    material,
    kdf: fastParams(material.request.salt),
    token: parsed.success ? parsed.data.session.token : null,
  };
}

export async function registerAccount(
  context: AuthContext,
  username: string,
  role: UserRole = 'member',
): Promise<TestAccount & { readonly token: string }> {
  const inviteToken = await createInviteToken(context, role);
  const account = await createTestAccount(context, { inviteToken, username });
  if (account.token === null) throw new Error(`Sign-up failed for ${username}`);
  return { ...account, token: account.token };
}

export function signInBody(account: TestAccount, loginKey?: Uint8Array): Record<string, unknown> {
  return {
    username: account.username,
    loginKey: encodeBytes(loginKey ?? account.material.request.loginKey),
    deviceLabel: DEFAULT_DEVICE_LABEL,
  };
}
