import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { createHash, randomBytes } from 'node:crypto';
import { vi } from 'vitest';
import {
  ROUTE_PATTERNS,
  deletionRequestResponse,
  encodeBytes,
  paramsResponse,
  passwordChangeResponse,
  recoveryStartResponse,
  sessionResponse,
  signInResponse,
  signUpResponse,
  userDto,
} from '@kodakiko/contract';
import {
  bytesEqual,
  createPasswordChangeMaterial,
  createRecoveryCompletion,
  deriveLoginMaterial,
  openKeyBundle,
  openRecovery,
} from '@kodakiko/core';
import { FAILED_SIGN_IN_BASE_DELAY_MS } from '../../src/config/constants.ts';
import { createAuthService } from '../../src/services/auth-service.ts';
import { createFailedSignInTracker } from '../../src/adapters/memory/failed-sign-in-tracker.ts';
import { silentLogger } from '../../src/platform/silent-logger.ts';
import {
  FAST_MEM_BYTES,
  FAST_OPS,
  FLOOR_MEM_BYTES,
  FLOOR_OPS,
  bytesOf,
  call,
  createAuthContext,
  createInviteToken,
  createMutableClock,
  createTestAccount,
  errorCodeOf,
  fastParams,
  registerAccount,
  signInBody,
  signUpBody,
  type AuthContext,
  type TestAccount,
} from './auth-helpers.ts';
import { createTestDatabase, type TestDatabase } from './helpers.ts';

const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;
const NEW_PASSWORD = 'a brand new passphrase';
const OTHER_NEW_PASSWORD = 'yet another passphrase';
const SHA256_BYTES = 32;
const SESSION_TOKEN_PREFIX_LENGTH = 6;

let testDatabase: TestDatabase;
let context: AuthContext;

beforeAll(async () => {
  testDatabase = await createTestDatabase();
  context = await createAuthContext(testDatabase);
});

afterAll(async () => {
  await context.close();
  await testDatabase.drop();
});

function bundleOf(json: unknown) {
  const parsed = signInResponse.parse(json);
  return {
    parsed,
    bundle: {
      salt: bytesOf(parsed.keyBundle.salt),
      opsLimit: parsed.keyBundle.opsLimit,
      memLimitBytes: parsed.keyBundle.memLimitBytes,
      masterKeyByKek: bytesOf(parsed.keyBundle.masterKeyByKek),
      recoveryKeyByMaster: bytesOf(parsed.keyBundle.recoveryKeyByMaster),
      publicKey: bytesOf(parsed.keyBundle.publicKey),
      privateKeyByMaster: bytesOf(parsed.keyBundle.privateKeyByMaster),
    },
  };
}

async function signIn(account: TestAccount, loginKey?: Uint8Array) {
  return call(context.app, 'POST', ROUTE_PATTERNS.authSignIn, {
    body: signInBody(account, loginKey),
  });
}

async function sessionStatus(token: string): Promise<number> {
  return (await call(context.app, 'GET', ROUTE_PATTERNS.authSession, { token })).status;
}

describe('1. bootstrap sign-up', () => {
  it('refuses the fast test parameters as too weak and creates nothing', async () => {
    const inviteToken = await createInviteToken(context, 'admin');
    const account = await createTestAccount(context, {
      inviteToken,
      username: 'bootstrap-weak',
      floor: false,
    });
    expect(account.response.status).toBe(422);
    expect(errorCodeOf(account.response)).toBe('KDF_PARAMS_TOO_WEAK');
    const rows = await context.db
      .selectFrom('users')
      .select('id')
      .where('username', '=', 'bootstrap-weak')
      .execute();
    expect(rows).toHaveLength(0);
  });

  it('creates an admin, the home collection and redeems the invite with the floor numbers', async () => {
    const inviteToken = await createInviteToken(context, 'admin');
    const account = await createTestAccount(context, { inviteToken, username: 'bootstrap-admin' });
    expect(account.response.status).toBe(201);
    const body = signUpResponse.parse(account.response.json);
    expect(body.user.role).toBe('admin');
    expect(body.user.status).toBe('active');
    expect(body.user.username).toBe('bootstrap-admin');
    expect(body.session.deviceLabel).toBe('Android');
    const home = await context.db
      .selectFrom('collections')
      .selectAll()
      .where('ownerId', '=', account.material.request.userId)
      .execute();
    expect(home).toHaveLength(1);
    expect(home[0]?.kind).toBe('home');
    expect(home[0]?.id).toBe(account.material.request.homeCollection.id);
    expect(home[0]?.lastSeq).toBe(0);
    const invite = await context.db
      .selectFrom('invites')
      .selectAll()
      .where('redeemedBy', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    expect(invite.redeemedAt).not.toBeNull();
  });

  it('answers 422 instead of a database error for out-of-range KDF numbers', async () => {
    const inviteToken = await createInviteToken(context, 'member');
    const account = await createTestAccount(context, { inviteToken, username: 'bootstrap-big' });
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authSignUp, {
      body: {
        ...signUpBody(account.material, { inviteToken }),
        userId: 'ZZZZZZZZZZZZZZZZZZZZZZ',
        username: 'bootstrap-big2',
        opsLimit: 2_147_483_648,
      },
    });
    expect(result.status).toBe(422);
    expect(errorCodeOf(result)).toBe('VALIDATION_FAILED');
  });

  it('answers 422 VALIDATION_FAILED when the KDF work product overflows the bound', async () => {
    const inviteToken = await createInviteToken(context, 'member');
    const account = await createTestAccount(context, {
      inviteToken,
      username: 'bootstrap-work',
      floor: false,
    });
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authSignUp, {
      body: {
        ...signUpBody(account.material, { inviteToken }),
        userId: 'UUUUUUUUUUUUUUUUUUUUUU',
        username: 'bootstrap-work2',
        opsLimit: 2_147_483_647,
        memLimitBytes: 2 ** 33,
      },
    });
    expect(result.status).toBe(422);
    expect(errorCodeOf(result)).toBe('VALIDATION_FAILED');
  });

  it('rejects wrong byte lengths with VALIDATION_FAILED', async () => {
    const inviteToken = await createInviteToken(context, 'member');
    const account = await createTestAccount(context, { inviteToken, username: 'bootstrap-len' });
    const short = encodeBytes(new Uint8Array(72));
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authSignUp, {
      body: {
        ...signUpBody(account.material, { inviteToken }),
        userId: 'YYYYYYYYYYYYYYYYYYYYYY',
        username: 'bootstrap-len2',
        masterKeyByKek: short,
      },
    });
    expect(result.status).toBe(422);
    expect(errorCodeOf(result)).toBe('VALIDATION_FAILED');
  });

  it('refuses a collection metadata blob above the configured size', async () => {
    const small = await createAuthContext(testDatabase, {
      env: { MAX_COLLECTION_METADATA_BYTES: '256' },
    });
    try {
      const inviteToken = await createInviteToken(small, 'member');
      const account = await createTestAccount(small, { inviteToken, username: 'bootstrap-meta' });
      const oversized = {
        ...signUpBody(account.material, { inviteToken }),
        userId: 'XXXXXXXXXXXXXXXXXXXXXX',
        username: 'bootstrap-meta2',
        homeCollection: {
          id: 'WWWWWWWWWWWWWWWWWWWWWW',
          keyByOwner: encodeBytes(account.material.request.homeCollection.keyByOwner),
          metadataBlob: encodeBytes(new Uint8Array(257)),
        },
      };
      const result = await call(small.app, 'POST', ROUTE_PATTERNS.authSignUp, { body: oversized });
      expect(result.status).toBe(422);
      expect(errorCodeOf(result)).toBe('VALIDATION_FAILED');
    } finally {
      await small.close();
    }
  });
});

describe('2. invites and sign-up modes (B-002)', () => {
  it('refuses a reused invite with INVITE_INVALID', async () => {
    const inviteToken = await createInviteToken(context, 'member');
    const first = await createTestAccount(context, { inviteToken, username: 'invite-first' });
    expect(first.response.status).toBe(201);
    const second = await createTestAccount(context, { inviteToken, username: 'invite-second' });
    expect(second.response.status).toBe(403);
    expect(errorCodeOf(second.response)).toBe('INVITE_INVALID');
  });

  it('refuses an expired invite, a missing invite and an unknown invite', async () => {
    const pastClock = createMutableClock(new Date(context.clock.now().getTime() - 10 * DAY_MS));
    const pastService = createAuthService({
      db: context.db,
      settings: context.settings,
      clock: pastClock,
      failedSignIns: createFailedSignInTracker({ windowMs: MINUTE_MS, maxEntries: 10 }),
      logger: silentLogger,
      checkOpenSignUpRate: async () => true,
    });
    const expired = await createInviteToken(context, 'member', 1, pastService);
    const expiredResult = await createTestAccount(context, {
      inviteToken: expired,
      username: 'invite-expired',
    });
    expect(expiredResult.response.status).toBe(403);
    expect(errorCodeOf(expiredResult.response)).toBe('INVITE_INVALID');
    const missing = await createTestAccount(context, { inviteToken: null, username: 'invite-none' });
    expect(missing.response.status).toBe(403);
    expect(errorCodeOf(missing.response)).toBe('INVITE_INVALID');
    const unknown = await createTestAccount(context, {
      inviteToken: `kdk_i_${'A'.repeat(43)}`,
      username: 'invite-unknown',
    });
    expect(unknown.response.status).toBe(403);
    expect(errorCodeOf(unknown.response)).toBe('INVITE_INVALID');
  });

  it('accepts sign-up without a token as a member in open mode', async () => {
    const open = await createAuthContext(testDatabase, { env: { SIGNUP_MODE: 'open' } });
    try {
      const account = await createTestAccount(open, { inviteToken: null, username: 'open-member' });
      expect(account.response.status).toBe(201);
      expect(signUpResponse.parse(account.response.json).user.role).toBe('member');
    } finally {
      await open.close();
    }
  });

  it('applies the open sign-up rate check first', async () => {
    const open = await createAuthContext(testDatabase, { env: { SIGNUP_MODE: 'open' } });
    try {
      const limited = createAuthService({
        db: open.db,
        settings: open.settings,
        clock: open.clock,
        failedSignIns: createFailedSignInTracker({ windowMs: MINUTE_MS, maxEntries: 10 }),
        logger: silentLogger,
        checkOpenSignUpRate: async () => false,
      });
      const account = await createTestAccount(open, { inviteToken: null, username: 'open-limited' });
      const { request } = account.material;
      await expect(
        limited.signUp({ ...request, inviteToken: null }, 'Android', '127.0.0.1'),
      ).rejects.toMatchObject({ code: 'RATE_LIMITED' });
    } finally {
      await open.close();
    }
  });
});

describe('open sign-up rate limit', () => {
  it('answers 429 RATE_LIMITED after five open sign-ups from one address', async () => {
    const open = await createAuthContext(testDatabase, {
      env: { SIGNUP_MODE: 'open', RATE_LIMITS_ENABLED: 'true' },
    });
    try {
      const OPEN_SIGN_UPS_PER_DAY = 5;
      for (let attempt = 0; attempt < OPEN_SIGN_UPS_PER_DAY; attempt += 1) {
        const account = await createTestAccount(open, {
          inviteToken: null,
          username: `open-rate-${attempt}`,
        });
        expect(account.response.status).toBe(201);
      }
      const sixth = await createTestAccount(open, { inviteToken: null, username: 'open-rate-5' });
      expect(sixth.response.status).toBe(429);
      expect(errorCodeOf(sixth.response)).toBe('RATE_LIMITED');
    } finally {
      await open.close();
    }
  });
});

describe('3. username and id conflicts', () => {
  it('answers 409 USERNAME_TAKEN and keeps the invite unredeemed', async () => {
    await registerAccount(context, 'taken-name');
    const inviteToken = await createInviteToken(context, 'member');
    const duplicate = await createTestAccount(context, { inviteToken, username: 'taken-name' });
    expect(duplicate.response.status).toBe(409);
    expect(errorCodeOf(duplicate.response)).toBe('USERNAME_TAKEN');
    const fresh = await createTestAccount(context, { inviteToken, username: 'taken-name-2' });
    expect(fresh.response.status).toBe(201);
  });

  it('answers 409 ID_CONFLICT for an existing user id', async () => {
    const first = await registerAccount(context, 'id-first');
    const inviteToken = await createInviteToken(context, 'member');
    const homeId = 'VVVVVVVVVVVVVVVVVVVVVV';
    const ids = [first.material.request.userId, homeId];
    const conflict = await createTestAccount(context, {
      inviteToken,
      username: 'id-second',
      randomId: () => ids.shift() ?? homeId,
    });
    expect(conflict.response.status).toBe(409);
    expect(errorCodeOf(conflict.response)).toBe('ID_CONFLICT');
  });

  it('answers 409 ID_CONFLICT for an existing home collection id and rolls everything back', async () => {
    const first = await registerAccount(context, 'home-first');
    const inviteToken = await createInviteToken(context, 'member');
    const ids = ['TTTTTTTTTTTTTTTTTTTTTT', first.material.request.homeCollection.id];
    const conflict = await createTestAccount(context, {
      inviteToken,
      username: 'home-second',
      randomId: () => ids.shift() ?? 'SSSSSSSSSSSSSSSSSSSSSS',
    });
    expect(conflict.response.status).toBe(409);
    expect(errorCodeOf(conflict.response)).toBe('ID_CONFLICT');
    const users = await context.db
      .selectFrom('users')
      .select('id')
      .where('username', '=', 'home-second')
      .execute();
    expect(users).toHaveLength(0);
    const retry = await createTestAccount(context, { inviteToken, username: 'home-second' });
    expect(retry.response.status).toBe(201);
  });

  it('lets exactly one of two concurrent sign-ups with one invite succeed', async () => {
    const inviteToken = await createInviteToken(context, 'member');
    const [left, right] = await Promise.all([
      createTestAccount(context, { inviteToken, username: 'race-left' }),
      createTestAccount(context, { inviteToken, username: 'race-right' }),
    ]);
    const statuses = [left.response.status, right.response.status].sort();
    expect(statuses).toEqual([201, 403]);
    const users = await context.db
      .selectFrom('users')
      .select('id')
      .where('username', 'in', ['race-left', 'race-right'])
      .execute();
    expect(users).toHaveLength(1);
  });

  it('answers 422 VALIDATION_FAILED for a username that is not normalized', async () => {
    const inviteToken = await createInviteToken(context, 'member');
    const account = await createTestAccount(context, { inviteToken, username: 'Alice' });
    expect(account.response.status).toBe(422);
    expect(errorCodeOf(account.response)).toBe('VALIDATION_FAILED');
  });
});

describe('4. params', () => {
  it('returns the stored params for a known user', async () => {
    const account = await registerAccount(context, 'params-known');
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authParams, {
      body: { username: 'params-known' },
    });
    expect(result.status).toBe(200);
    const params = paramsResponse.parse(result.json);
    expect(bytesEqual(bytesOf(params.salt), account.material.request.salt)).toBe(true);
    expect(params.opsLimit).toBe(FLOOR_OPS);
    expect(params.memLimitBytes).toBe(FLOOR_MEM_BYTES);
  });

  it('returns deterministic fake params for unknown users with the same shape', async () => {
    await registerAccount(context, 'params-shape');
    const known = await call(context.app, 'POST', ROUTE_PATTERNS.authParams, {
      body: { username: 'params-shape' },
    });
    const unknownBody = { username: 'params-nobody' };
    const first = await call(context.app, 'POST', ROUTE_PATTERNS.authParams, { body: unknownBody });
    const second = await call(context.app, 'POST', ROUTE_PATTERNS.authParams, { body: unknownBody });
    const other = await call(context.app, 'POST', ROUTE_PATTERNS.authParams, {
      body: { username: 'params-somebody' },
    });
    expect(first.status).toBe(200);
    expect(first.raw).toBe(second.raw);
    expect(first.raw).not.toBe(other.raw);
    const fake = paramsResponse.parse(first.json);
    expect(bytesOf(fake.salt)).toHaveLength(16);
    expect(fake.opsLimit).toBe(16);
    expect(fake.memLimitBytes).toBe(268_435_456);
    expect(Object.keys(first.json as object).sort()).toEqual(Object.keys(known.json as object).sort());
  });

  it('14. answers a malformed username with fake params, not a 422', async () => {
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authParams, {
      body: { username: 'Not A Valid Name!' },
    });
    expect(result.status).toBe(200);
    paramsResponse.parse(result.json);
  });
});

describe('unauthenticated routes are keyed by address', () => {
  it('shares one bucket on /auth/params and /auth/recovery/start whatever bearer token is sent', async () => {
    const limited = await createAuthContext(testDatabase, { env: { RATE_LIMITS_ENABLED: 'true' } });
    try {
      const PARAMS_PER_MINUTE = 60;
      const randomSessionToken = () => `kdk_s_${randomBytes(32).toString('base64url')}`;
      for (let attempt = 0; attempt < PARAMS_PER_MINUTE; attempt += 1) {
        const response = await limited.app.inject({
          method: 'POST',
          url: ROUTE_PATTERNS.authParams,
          headers: { authorization: `Bearer ${randomSessionToken()}` },
          payload: { username: 'rate-probe' },
        });
        expect(response.statusCode).toBe(200);
      }
      const over = await limited.app.inject({
        method: 'POST',
        url: ROUTE_PATTERNS.authParams,
        headers: { authorization: `Bearer ${randomSessionToken()}` },
        payload: { username: 'rate-probe' },
      });
      expect(over.statusCode).toBe(429);
      const recovery = await limited.app.inject({
        method: 'POST',
        url: ROUTE_PATTERNS.authRecoveryStart,
        headers: { authorization: `Bearer ${randomSessionToken()}` },
        payload: { username: 'rate-probe' },
      });
      expect(recovery.statusCode).toBe(200);
    } finally {
      await limited.close();
    }
  });
});

describe('5. sign-in (B-004)', () => {
  it('returns a key bundle that opens with the password-derived kek', async () => {
    const account = await registerAccount(context, 'signin-ok');
    const result = await signIn(account);
    expect(result.status).toBe(200);
    const { parsed, bundle } = bundleOf(result.json);
    expect(parsed.user.username).toBe('signin-ok');
    expect(parsed.session.token).not.toBe(account.token);
    const material = await deriveLoginMaterial(context.sodium, account.password, account.kdf);
    const keys = openKeyBundle(context.sodium, material.kek, account.material.request.userId, bundle);
    expect(bytesEqual(keys.masterKey, account.material.secrets.masterKey)).toBe(true);
    expect(bytesEqual(keys.privateKey, account.material.secrets.privateKey)).toBe(true);
  });

  it('answers a wrong key and an unknown user with the same status and body shape', async () => {
    const account = await registerAccount(context, 'signin-wrong');
    const wrong = await signIn(account, new Uint8Array(32).fill(9));
    const unknown = await call(context.app, 'POST', ROUTE_PATTERNS.authSignIn, {
      body: { ...signInBody(account), username: 'signin-nobody' },
    });
    expect(wrong.status).toBe(401);
    expect(unknown.status).toBe(401);
    expect(errorCodeOf(wrong)).toBe('INVALID_CREDENTIALS');
    expect(errorCodeOf(unknown)).toBe('INVALID_CREDENTIALS');
    const strip = (raw: string) => raw.replace(/"requestId":"[^"]*"/, '');
    expect(strip(wrong.raw)).toBe(strip(unknown.raw));
  });

  it('stores only a 32-byte hash of the login key', async () => {
    const account = await registerAccount(context, 'signin-hash');
    const row = await context.db
      .selectFrom('users')
      .selectAll()
      .where('id', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    expect(row.loginKeyHash).toHaveLength(SHA256_BYTES);
    expect(row.loginKeyHash.equals(createHash('sha256').update(account.material.request.loginKey).digest())).toBe(true);
    expect(row.recoveryLoginKeyHash).toHaveLength(SHA256_BYTES);
    for (const value of Object.values(row)) {
      if (Buffer.isBuffer(value)) {
        expect(value.equals(Buffer.from(account.material.request.loginKey))).toBe(false);
        expect(value.equals(Buffer.from(account.material.request.recoveryLoginKey))).toBe(false);
      }
    }
  });
});

describe('6. failed sign-in throttle', () => {
  it('delays the attempt by the base delay after the maximum failures and logs a hashed username', async () => {
    const strict = await createAuthContext(testDatabase, { env: { MAX_FAILED_SIGN_INS: '1' } });
    const nativeSetTimeout = globalThis.setTimeout;
    const requestedDelays: number[] = [];
    const spy = vi.spyOn(globalThis, 'setTimeout').mockImplementation(((
      ...args: Parameters<typeof setTimeout>
    ) => {
      const [handler, delay, ...rest] = args;
      if (delay !== FAILED_SIGN_IN_BASE_DELAY_MS) return nativeSetTimeout(handler, delay, ...rest);
      requestedDelays.push(delay);
      return nativeSetTimeout(handler, 0, ...rest);
    }) as typeof setTimeout);
    try {
      const account = await registerAccount(strict, 'throttle-user');
      const wrongKey = new Uint8Array(32).fill(5);
      const first = await call(strict.app, 'POST', ROUTE_PATTERNS.authSignIn, {
        body: signInBody(account, wrongKey),
      });
      expect(first.status).toBe(401);
      expect(requestedDelays).toEqual([]);
      const second = await call(strict.app, 'POST', ROUTE_PATTERNS.authSignIn, {
        body: signInBody(account, wrongKey),
      });
      expect(second.status).toBe(401);
      expect(requestedDelays).toEqual([FAILED_SIGN_IN_BASE_DELAY_MS]);
      const logs = strict.logs.text();
      expect(logs).toContain('auth.throttled');
      expect(logs).toContain('auth.sign_in_failed');
      expect(logs).not.toContain('throttle-user');
    } finally {
      spy.mockRestore();
      await strict.close();
    }
  });

  it('clears the failures after a successful sign-in', async () => {
    const strict = await createAuthContext(testDatabase, { env: { MAX_FAILED_SIGN_INS: '2' } });
    try {
      const account = await registerAccount(strict, 'throttle-clear');
      const wrongKey = new Uint8Array(32).fill(5);
      await call(strict.app, 'POST', ROUTE_PATTERNS.authSignIn, { body: signInBody(account, wrongKey) });
      const ok = await call(strict.app, 'POST', ROUTE_PATTERNS.authSignIn, { body: signInBody(account) });
      expect(ok.status).toBe(200);
      await call(strict.app, 'POST', ROUTE_PATTERNS.authSignIn, { body: signInBody(account, wrongKey) });
      const next = await call(strict.app, 'POST', ROUTE_PATTERNS.authSignIn, { body: signInBody(account) });
      expect(next.status).toBe(200);
    } finally {
      await strict.close();
    }
  });
});

describe('7. sessions', () => {
  it('answers 401 UNAUTHENTICATED for a missing or garbage header', async () => {
    const missing = await call(context.app, 'GET', ROUTE_PATTERNS.authSession);
    expect(missing.status).toBe(401);
    expect(errorCodeOf(missing)).toBe('UNAUTHENTICATED');
    for (const token of ['garbage', 'kdk_s_short', `kdk_s_${'A'.repeat(43)}`]) {
      const result = await call(context.app, 'GET', ROUTE_PATTERNS.authSession, { token });
      expect(result.status).toBe(401);
      expect(errorCodeOf(result)).toBe('UNAUTHENTICATED');
    }
    const wrongScheme = await context.app.inject({
      method: 'GET',
      url: ROUTE_PATTERNS.authSession,
      headers: { authorization: 'Basic abc' },
    });
    expect(wrongScheme.statusCode).toBe(401);
  });

  it('answers 401 SESSION_EXPIRED for an expired session and deletes its row', async () => {
    const account = await registerAccount(context, 'session-expire');
    context.clock.advance(31 * DAY_MS);
    const result = await call(context.app, 'GET', ROUTE_PATTERNS.authSession, { token: account.token });
    expect(result.status).toBe(401);
    expect(errorCodeOf(result)).toBe('SESSION_EXPIRED');
    const rows = await context.db
      .selectFrom('sessions')
      .select('id')
      .where('userId', '=', account.material.request.userId)
      .execute();
    expect(rows).toHaveLength(0);
    context.clock.advance(-31 * DAY_MS);
  });

  it('touches last_used_at at most every 5 minutes', async () => {
    const account = await registerAccount(context, 'session-touch');
    const read = () =>
      context.db
        .selectFrom('sessions')
        .select(['lastUsedAt', 'expiresAt'])
        .where('userId', '=', account.material.request.userId)
        .executeTakeFirstOrThrow();
    const initial = await read();
    context.clock.advance(4 * MINUTE_MS);
    expect(await sessionStatus(account.token)).toBe(200);
    expect((await read()).lastUsedAt.getTime()).toBe(initial.lastUsedAt.getTime());
    context.clock.advance(2 * MINUTE_MS);
    expect(await sessionStatus(account.token)).toBe(200);
    const touched = await read();
    expect(touched.lastUsedAt.getTime()).toBe(context.clock.now().getTime());
    expect(touched.expiresAt.getTime()).toBe(context.clock.now().getTime() + 30 * DAY_MS);
  });

  it('returns the user for GET /auth/session and GET /users/me', async () => {
    const account = await registerAccount(context, 'session-me');
    const session = await call(context.app, 'GET', ROUTE_PATTERNS.authSession, { token: account.token });
    expect(sessionResponse.parse(session.json).user.username).toBe('session-me');
    const me = await call(context.app, 'GET', ROUTE_PATTERNS.usersMe, { token: account.token });
    expect(userDto.parse(me.json).id).toBe(account.material.request.userId);
  });

  it('serves the key bundle to a signed-in session', async () => {
    const account = await registerAccount(context, 'session-bundle');
    const result = await call(context.app, 'GET', ROUTE_PATTERNS.authKeyBundle, { token: account.token });
    expect(result.status).toBe(200);
    expect(bytesOf((result.json as { masterKeyByKek: unknown }).masterKeyByKek)).toEqual(
      account.material.request.masterKeyByKek,
    );
  });
});

describe('8. password change', () => {
  it('revokes the other session, returns a new token and swaps the password', async () => {
    const account = await registerAccount(context, 'change-ok');
    const other = signInResponse.parse((await signIn(account)).json).session.token;
    const change = await createPasswordChangeMaterial(
      context.sodium,
      {
        userId: account.material.request.userId,
        currentPassword: account.password,
        newPassword: NEW_PASSWORD,
        currentParams: account.kdf,
        cachedMasterKeyByKek: account.material.request.masterKeyByKek,
      },
      fastParams,
    );
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authPassword, {
      token: account.token,
      body: {
        currentLoginKey: encodeBytes(change.currentLoginKey),
        salt: encodeBytes(change.salt),
        opsLimit: FLOOR_OPS,
        memLimitBytes: FLOOR_MEM_BYTES,
        loginKey: encodeBytes(change.loginKey),
        masterKeyByKek: encodeBytes(change.masterKeyByKek),
      },
    });
    expect(result.status).toBe(200);
    const fresh = passwordChangeResponse.parse(result.json).session;
    expect(fresh.deviceLabel).toBe('Android');
    expect(fresh.token).not.toBe(account.token);
    expect(await sessionStatus(other)).toBe(401);
    expect(await sessionStatus(account.token)).toBe(401);
    expect(await sessionStatus(fresh.token)).toBe(200);
    const oldPassword = await signIn(account);
    expect(oldPassword.status).toBe(401);
    const newPassword = await call(context.app, 'POST', ROUTE_PATTERNS.authSignIn, {
      body: { username: 'change-ok', loginKey: encodeBytes(change.loginKey), deviceLabel: 'Android' },
    });
    expect(newPassword.status).toBe(200);
    const { bundle } = bundleOf(newPassword.json);
    const material = await deriveLoginMaterial(context.sodium, NEW_PASSWORD, {
      salt: change.salt,
      opsLimit: FAST_OPS,
      memLimitBytes: FAST_MEM_BYTES,
    });
    const keys = openKeyBundle(context.sodium, material.kek, account.material.request.userId, bundle);
    expect(bytesEqual(keys.masterKey, account.material.secrets.masterKey)).toBe(true);
  });

  it('15. answers 401 INVALID_CREDENTIALS for a wrong currentLoginKey and changes nothing', async () => {
    const account = await registerAccount(context, 'change-wrong');
    const before = await context.db
      .selectFrom('users')
      .select(['loginKeyHash', 'masterKeyByKek', 'kdfSalt'])
      .where('id', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authPassword, {
      token: account.token,
      body: {
        currentLoginKey: encodeBytes(new Uint8Array(32).fill(3)),
        salt: encodeBytes(new Uint8Array(16).fill(4)),
        opsLimit: FLOOR_OPS,
        memLimitBytes: FLOOR_MEM_BYTES,
        loginKey: encodeBytes(new Uint8Array(32).fill(6)),
        masterKeyByKek: encodeBytes(new Uint8Array(73).fill(7)),
      },
    });
    expect(result.status).toBe(401);
    expect(errorCodeOf(result)).toBe('INVALID_CREDENTIALS');
    const after = await context.db
      .selectFrom('users')
      .select(['loginKeyHash', 'masterKeyByKek', 'kdfSalt'])
      .where('id', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    expect(after.loginKeyHash.equals(before.loginKeyHash)).toBe(true);
    expect(after.masterKeyByKek.equals(before.masterKeyByKek)).toBe(true);
    expect(after.kdfSalt.equals(before.kdfSalt)).toBe(true);
    expect(await sessionStatus(account.token)).toBe(200);
  });

  it('throws UNAUTHENTICATED and changes nothing when the calling session was revoked', async () => {
    const account = await registerAccount(context, 'change-revoked');
    const session = await context.db
      .selectFrom('sessions')
      .select('id')
      .where('userId', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    await call(context.app, 'POST', ROUTE_PATTERNS.authSignOut, { token: account.token });
    const before = await context.db
      .selectFrom('users')
      .select(['loginKeyHash', 'masterKeyByKek'])
      .where('id', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    await expect(
      context.inviteService.changePassword(
        account.material.request.userId,
        session.id,
        'Android',
        {
          currentLoginKey: account.material.request.loginKey,
          salt: new Uint8Array(16).fill(4),
          opsLimit: FLOOR_OPS,
          memLimitBytes: FLOOR_MEM_BYTES,
          loginKey: new Uint8Array(32).fill(6),
          masterKeyByKek: new Uint8Array(73).fill(7),
        },
      ),
    ).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    const after = await context.db
      .selectFrom('users')
      .select(['loginKeyHash', 'masterKeyByKek'])
      .where('id', '=', account.material.request.userId)
      .executeTakeFirstOrThrow();
    expect(after.loginKeyHash.equals(before.loginKeyHash)).toBe(true);
    expect(after.masterKeyByKek.equals(before.masterKeyByKek)).toBe(true);
  });

  it('refuses new params below the floor', async () => {
    const account = await registerAccount(context, 'change-weak');
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authPassword, {
      token: account.token,
      body: {
        currentLoginKey: encodeBytes(account.material.request.loginKey),
        salt: encodeBytes(new Uint8Array(16).fill(4)),
        opsLimit: FAST_OPS,
        memLimitBytes: FAST_MEM_BYTES,
        loginKey: encodeBytes(new Uint8Array(32).fill(6)),
        masterKeyByKek: encodeBytes(new Uint8Array(73).fill(7)),
      },
    });
    expect(result.status).toBe(422);
    expect(errorCodeOf(result)).toBe('KDF_PARAMS_TOO_WEAK');
  });
});

describe('9. recovery', () => {
  it('start returns the userId and a wrap that opens with the words', async () => {
    const account = await registerAccount(context, 'recover-start');
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authRecoveryStart, {
      body: { username: 'recover-start' },
    });
    expect(result.status).toBe(200);
    const parsed = recoveryStartResponse.parse(result.json);
    expect(parsed.userId).toBe(account.material.request.userId);
    const opened = openRecovery(
      context.sodium,
      account.material.recoveryWords,
      parsed.userId,
      bytesOf(parsed.masterKeyByRecovery),
    );
    expect(bytesEqual(opened.masterKey, account.material.secrets.masterKey)).toBe(true);
  });

  it('returns identical fake bytes for an unknown username', async () => {
    const body = { username: 'recover-nobody' };
    const first = await call(context.app, 'POST', ROUTE_PATTERNS.authRecoveryStart, { body });
    const second = await call(context.app, 'POST', ROUTE_PATTERNS.authRecoveryStart, { body });
    const other = await call(context.app, 'POST', ROUTE_PATTERNS.authRecoveryStart, {
      body: { username: 'recover-nobody-2' },
    });
    expect(first.status).toBe(200);
    expect(first.raw).toBe(second.raw);
    expect(first.raw).not.toBe(other.raw);
    const parsed = recoveryStartResponse.parse(first.json);
    const wrap = bytesOf(parsed.masterKeyByRecovery);
    expect(wrap).toHaveLength(73);
    expect(wrap[0]).toBe(1);
  });

  it('completes with the right recovery key and revokes every old session', async () => {
    const account = await registerAccount(context, 'recover-done');
    const second = signInResponse.parse((await signIn(account)).json).session.token;
    const opened = openRecovery(
      context.sodium,
      account.material.recoveryWords,
      account.material.request.userId,
      account.material.request.masterKeyByRecovery,
    );
    const completion = await createRecoveryCompletion(
      context.sodium,
      {
        userId: account.material.request.userId,
        newPassword: NEW_PASSWORD,
        masterKey: opened.masterKey,
        recoveryKey: opened.recoveryKey,
      },
      fastParams,
    );
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authRecoveryComplete, {
      body: {
        username: 'recover-done',
        recoveryLoginKey: encodeBytes(completion.recoveryLoginKey),
        deviceLabel: 'Android',
        salt: encodeBytes(completion.salt),
        opsLimit: FLOOR_OPS,
        memLimitBytes: FLOOR_MEM_BYTES,
        loginKey: encodeBytes(completion.loginKey),
        masterKeyByKek: encodeBytes(completion.masterKeyByKek),
      },
    });
    expect(result.status).toBe(200);
    const parsed = signInResponse.parse(result.json);
    expect(await sessionStatus(account.token)).toBe(401);
    expect(await sessionStatus(second)).toBe(401);
    expect(await sessionStatus(parsed.session.token)).toBe(200);
    expect((await signIn(account)).status).toBe(401);
    const newSignIn = await call(context.app, 'POST', ROUTE_PATTERNS.authSignIn, {
      body: { username: 'recover-done', loginKey: encodeBytes(completion.loginKey), deviceLabel: 'Android' },
    });
    expect(newSignIn.status).toBe(200);
  });

  it('answers 401 INVALID_CREDENTIALS for a wrong recovery login key', async () => {
    const account = await registerAccount(context, 'recover-wrong');
    const result = await call(context.app, 'POST', ROUTE_PATTERNS.authRecoveryComplete, {
      body: {
        username: 'recover-wrong',
        recoveryLoginKey: encodeBytes(new Uint8Array(32).fill(8)),
        deviceLabel: 'Android',
        salt: encodeBytes(new Uint8Array(16).fill(4)),
        opsLimit: FLOOR_OPS,
        memLimitBytes: FLOOR_MEM_BYTES,
        loginKey: encodeBytes(new Uint8Array(32).fill(6)),
        masterKeyByKek: encodeBytes(new Uint8Array(73).fill(7)),
      },
    });
    expect(result.status).toBe(401);
    expect(errorCodeOf(result)).toBe('INVALID_CREDENTIALS');
    expect(await sessionStatus(account.token)).toBe(200);
    const logs = context.logs.text();
    const usernameHashPrefix = createHash('sha256').update('recover-wrong').digest('hex').slice(0, 12);
    expect(logs).toContain('auth.recovery_failed');
    expect(logs).toContain(usernameHashPrefix);
    expect(logs).not.toContain('recover-wrong');
  });
});

describe('10. account deletion', () => {
  it('revokes sessions, limits routes while pending and allows cancel before the due date', async () => {
    const account = await registerAccount(context, 'delete-cancel');
    const other = signInResponse.parse((await signIn(account)).json).session.token;
    const request = await call(context.app, 'POST', ROUTE_PATTERNS.usersMeDelete, {
      token: account.token,
    });
    expect(request.status).toBe(202);
    const due = new Date(deletionRequestResponse.parse(request.json).deletionDueAt);
    expect(due.getTime()).toBe(context.clock.now().getTime() + 7 * DAY_MS);
    expect(await sessionStatus(account.token)).toBe(401);
    expect(await sessionStatus(other)).toBe(401);

    const pending = await signIn(account);
    expect(pending.status).toBe(200);
    const pendingSession = signInResponse.parse(pending.json);
    expect(pendingSession.user.status).toBe('pending_deletion');
    expect(pendingSession.user.deletionDueAt).toBe(due.toISOString());

    const bundle = await call(context.app, 'GET', ROUTE_PATTERNS.authKeyBundle, {
      token: pendingSession.session.token,
    });
    expect(bundle.status).toBe(403);
    expect(errorCodeOf(bundle)).toBe('ACCOUNT_PENDING_DELETION');
    const password = await call(context.app, 'POST', ROUTE_PATTERNS.authPassword, {
      token: pendingSession.session.token,
      body: {
        currentLoginKey: encodeBytes(account.material.request.loginKey),
        salt: encodeBytes(new Uint8Array(16).fill(4)),
        opsLimit: FLOOR_OPS,
        memLimitBytes: FLOOR_MEM_BYTES,
        loginKey: encodeBytes(new Uint8Array(32).fill(6)),
        masterKeyByKek: encodeBytes(new Uint8Array(73).fill(7)),
      },
    });
    expect(password.status).toBe(403);
    expect(errorCodeOf(password)).toBe('ACCOUNT_PENDING_DELETION');
    const me = await call(context.app, 'GET', ROUTE_PATTERNS.usersMe, {
      token: pendingSession.session.token,
    });
    expect(me.status).toBe(200);
    expect(await sessionStatus(pendingSession.session.token)).toBe(200);

    const cancel = await call(context.app, 'POST', ROUTE_PATTERNS.usersMeDeleteCancel, {
      token: pendingSession.session.token,
    });
    expect(cancel.status).toBe(200);
    const restored = userDto.parse(cancel.json);
    expect(restored.status).toBe('active');
    expect(restored.deletionDueAt).toBeNull();
    const again = await call(context.app, 'GET', ROUTE_PATTERNS.authKeyBundle, {
      token: pendingSession.session.token,
    });
    expect(again.status).toBe(200);
  });

  it('refuses cancel after the due date and when not pending', async () => {
    const account = await registerAccount(context, 'delete-late');
    const notPending = await call(context.app, 'POST', ROUTE_PATTERNS.usersMeDeleteCancel, {
      token: account.token,
    });
    expect(notPending.status).toBe(403);
    expect(errorCodeOf(notPending)).toBe('FORBIDDEN');
    await call(context.app, 'POST', ROUTE_PATTERNS.usersMeDelete, { token: account.token });
    const pending = signInResponse.parse((await signIn(account)).json).session.token;
    context.clock.advance(8 * DAY_MS);
    const late = await call(context.app, 'POST', ROUTE_PATTERNS.usersMeDeleteCancel, {
      token: pending,
    });
    expect(late.status).toBe(403);
    expect(errorCodeOf(late)).toBe('FORBIDDEN');
    context.clock.advance(-8 * DAY_MS);
  });
});

describe('11. logs', () => {
  it('never contain login keys, tokens, invite tokens or wraps', async () => {
    const logged = await createAuthContext(testDatabase);
    try {
      const inviteToken = await createInviteToken(logged, 'member');
      const registered = await createTestAccount(logged, { inviteToken, username: 'logs-user' });
      const account = { ...registered, token: signUpResponse.parse(registered.response.json).session.token };
      const signInResult = await call(logged.app, 'POST', ROUTE_PATTERNS.authSignIn, {
        body: signInBody(account),
      });
      const signedIn = signInResponse.parse(signInResult.json);
      await call(logged.app, 'POST', ROUTE_PATTERNS.authSignIn, {
        body: signInBody(account, new Uint8Array(32).fill(2)),
      });
      const change = await createPasswordChangeMaterial(
        logged.sodium,
        {
          userId: account.material.request.userId,
          currentPassword: account.password,
          newPassword: NEW_PASSWORD,
          currentParams: account.kdf,
          cachedMasterKeyByKek: account.material.request.masterKeyByKek,
        },
        fastParams,
      );
      const changed = await call(logged.app, 'POST', ROUTE_PATTERNS.authPassword, {
        token: account.token,
        body: {
          currentLoginKey: encodeBytes(change.currentLoginKey),
          salt: encodeBytes(change.salt),
          opsLimit: FLOOR_OPS,
          memLimitBytes: FLOOR_MEM_BYTES,
          loginKey: encodeBytes(change.loginKey),
          masterKeyByKek: encodeBytes(change.masterKeyByKek),
        },
      });
      const fresh = passwordChangeResponse.parse(changed.json).session.token;

      const start = await call(logged.app, 'POST', ROUTE_PATTERNS.authRecoveryStart, {
        body: { username: 'logs-user' },
      });
      const started = recoveryStartResponse.parse(start.json);
      const opened = openRecovery(
        logged.sodium,
        account.material.recoveryWords,
        started.userId,
        bytesOf(started.masterKeyByRecovery),
      );
      const completion = await createRecoveryCompletion(
        logged.sodium,
        {
          userId: started.userId,
          newPassword: OTHER_NEW_PASSWORD,
          masterKey: opened.masterKey,
          recoveryKey: opened.recoveryKey,
        },
        fastParams,
      );
      const completed = await call(logged.app, 'POST', ROUTE_PATTERNS.authRecoveryComplete, {
        body: {
          username: 'logs-user',
          recoveryLoginKey: encodeBytes(completion.recoveryLoginKey),
          deviceLabel: 'Android',
          salt: encodeBytes(completion.salt),
          opsLimit: FLOOR_OPS,
          memLimitBytes: FLOOR_MEM_BYTES,
          loginKey: encodeBytes(completion.loginKey),
          masterKeyByKek: encodeBytes(completion.masterKeyByKek),
        },
      });
      expect(completed.status).toBe(200);
      const recovered = signInResponse.parse(completed.json).session.token;

      const logs = logged.logs.text();
      expect(logs).toContain('auth.signed_up');
      expect(logs).toContain('auth.password_changed');
      expect(logs).toContain('auth.recovered');
      const secrets = [
        inviteToken,
        inviteToken.slice(SESSION_TOKEN_PREFIX_LENGTH),
        encodeBytes(account.material.request.loginKey),
        encodeBytes(account.material.request.recoveryLoginKey),
        encodeBytes(change.loginKey),
        encodeBytes(change.currentLoginKey),
        encodeBytes(change.masterKeyByKek),
        encodeBytes(completion.loginKey),
        encodeBytes(completion.masterKeyByKek),
        encodeBytes(account.material.request.masterKeyByKek),
        encodeBytes(account.material.request.masterKeyByRecovery),
        encodeBytes(account.material.request.privateKeyByMaster),
        started.masterKeyByRecovery,
        account.token,
        signedIn.session.token,
        fresh,
        recovered,
        account.token.slice(SESSION_TOKEN_PREFIX_LENGTH),
        fresh.slice(SESSION_TOKEN_PREFIX_LENGTH),
        recovered.slice(SESSION_TOKEN_PREFIX_LENGTH),
      ];
      for (const secret of secrets) expect(logs).not.toContain(secret);
    } finally {
      await logged.close();
    }
  });
});

describe('13. sign-out', () => {
  it('answers 204 and the token is refused afterwards', async () => {
    const account = await registerAccount(context, 'signout-user');
    const out = await call(context.app, 'POST', ROUTE_PATTERNS.authSignOut, { token: account.token });
    expect(out.status).toBe(204);
    expect(await sessionStatus(account.token)).toBe(401);
  });
});
