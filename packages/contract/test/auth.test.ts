import { describe, expect, it } from 'vitest';
import {
  DEVICE_LABEL_MAX_CHARS,
  INVITE_TOKEN_PATTERN,
  INVITE_TOKEN_PREFIX,
  KDF_OPS_LIMIT_MAX,
  KDF_WORK_MAX,
  SESSION_TOKEN_PREFIX,
  TOKEN_RANDOM_BYTES,
  USERNAME_INPUT_MAX,
  USERNAME_PATTERN,
  USER_ROLES,
  USER_STATUSES,
  ROUTE_PATTERNS,
  deletionRequestResponse,
  keyBundleDto,
  paramsRequest,
  paramsResponse,
  passwordChangeRequest,
  passwordChangeResponse,
  recoveryCompleteRequest,
  recoveryStartRequest,
  recoveryStartResponse,
  sessionDto,
  sessionResponse,
  signInRequest,
  signInResponse,
  signUpRequest,
  signUpResponse,
  userDto,
} from '../src/index.ts';

const ID = 'AAAAAAAAAAAAAAAAAAAAAA';
const BYTES = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const MOMENT = '2026-11-12T10:00:00.000Z';
const SESSION_TOKEN = `${SESSION_TOKEN_PREFIX}${'A'.repeat(43)}`;
const INVITE_TOKEN = `${INVITE_TOKEN_PREFIX}${'B'.repeat(43)}`;

function signUpBody(overrides: Record<string, unknown> = {}) {
  return {
    userId: ID,
    username: 'alice',
    inviteToken: INVITE_TOKEN,
    deviceLabel: 'Android',
    salt: BYTES,
    opsLimit: 32,
    memLimitBytes: 134217728,
    loginKey: BYTES,
    recoveryLoginKey: BYTES,
    publicKey: BYTES,
    masterKeyByKek: BYTES,
    masterKeyByRecovery: BYTES,
    recoveryKeyByMaster: BYTES,
    privateKeyByMaster: BYTES,
    homeCollection: { id: ID, keyByOwner: BYTES, metadataBlob: BYTES },
    ...overrides,
  };
}

function user(overrides: Record<string, unknown> = {}) {
  return {
    id: ID,
    username: 'alice',
    role: 'admin',
    status: 'active',
    quotaBytes: null,
    usedBytes: 0,
    createdAt: MOMENT,
    deletionDueAt: null,
    ...overrides,
  };
}

function session() {
  return { token: SESSION_TOKEN, expiresAt: MOMENT, deviceLabel: 'Android' };
}

function keyBundle() {
  return {
    salt: BYTES,
    opsLimit: 32,
    memLimitBytes: 134217728,
    masterKeyByKek: BYTES,
    recoveryKeyByMaster: BYTES,
    publicKey: BYTES,
    privateKeyByMaster: BYTES,
  };
}

describe('auth limits and enums', () => {
  it('has the planned values', () => {
    expect(USERNAME_PATTERN).toBe('^[a-z0-9._-]{3,32}$');
    expect(DEVICE_LABEL_MAX_CHARS).toBe(64);
    expect(SESSION_TOKEN_PREFIX).toBe('kdk_s_');
    expect(INVITE_TOKEN_PREFIX).toBe('kdk_i_');
    expect(TOKEN_RANDOM_BYTES).toBe(32);
    expect(USERNAME_INPUT_MAX).toBe(128);
    expect(KDF_WORK_MAX).toBe(2 ** 62);
    expect(INVITE_TOKEN_PATTERN).toBe('^kdk_i_[A-Za-z0-9_-]{43}$');
    expect(USER_STATUSES).toEqual(['active', 'pending_deletion']);
    expect(USER_ROLES).toEqual(['admin', 'member']);
  });
});

describe('signUpRequest', () => {
  it('accepts a complete body and a null invite token', () => {
    expect(signUpRequest.safeParse(signUpBody()).success).toBe(true);
    expect(signUpRequest.safeParse(signUpBody({ inviteToken: null })).success).toBe(true);
  });

  it('rejects a username that is not normalized or too short', () => {
    expect(signUpRequest.safeParse(signUpBody({ username: 'Alice' })).success).toBe(false);
    expect(signUpRequest.safeParse(signUpBody({ username: 'ab' })).success).toBe(false);
  });

  it('rejects a malformed invite token, device label and user id', () => {
    expect(signUpRequest.safeParse(signUpBody({ inviteToken: 'kdk_i_short' })).success).toBe(false);
    expect(signUpRequest.safeParse(signUpBody({ deviceLabel: '' })).success).toBe(false);
    expect(
      signUpRequest.safeParse(signUpBody({ deviceLabel: 'x'.repeat(DEVICE_LABEL_MAX_CHARS + 1) }))
        .success,
    ).toBe(false);
    expect(signUpRequest.safeParse(signUpBody({ userId: 'short' })).success).toBe(false);
  });

  it('leaves the KDF floor to the server but bounds the numbers', () => {
    expect(signUpRequest.safeParse(signUpBody({ opsLimit: 1, memLimitBytes: 1 })).success).toBe(
      true,
    );
    expect(signUpRequest.safeParse(signUpBody({ opsLimit: 0 })).success).toBe(false);
    expect(signUpRequest.safeParse(signUpBody({ memLimitBytes: 0 })).success).toBe(false);
    expect(signUpRequest.safeParse(signUpBody({ opsLimit: KDF_OPS_LIMIT_MAX })).success).toBe(true);
    expect(signUpRequest.safeParse(signUpBody({ opsLimit: KDF_OPS_LIMIT_MAX + 1 })).success).toBe(
      false,
    );
    expect(
      signUpRequest.safeParse(signUpBody({ memLimitBytes: Number.MAX_SAFE_INTEGER + 1 })).success,
    ).toBe(false);
  });

  it('rejects binary fields that are not base64url', () => {
    expect(signUpRequest.safeParse(signUpBody({ loginKey: 'not base64!' })).success).toBe(false);
    expect(signUpRequest.safeParse(signUpBody({ loginKey: '' })).success).toBe(false);
  });
});

describe('lenient username inputs', () => {
  it('accepts any 1-128 character name on params, sign-in and recovery start', () => {
    const name = 'Not A Valid Name!';
    expect(paramsRequest.safeParse({ username: name }).success).toBe(true);
    expect(recoveryStartRequest.safeParse({ username: name }).success).toBe(true);
    expect(
      signInRequest.safeParse({ username: name, loginKey: BYTES, deviceLabel: 'Android' }).success,
    ).toBe(true);
  });

  it('rejects empty and over-long names', () => {
    expect(paramsRequest.safeParse({ username: '' }).success).toBe(false);
    expect(paramsRequest.safeParse({ username: 'a'.repeat(USERNAME_INPUT_MAX + 1) }).success).toBe(
      false,
    );
  });
});

describe('request and response schemas', () => {
  it('parses params, recovery and password bodies', () => {
    expect(
      paramsResponse.safeParse({ salt: BYTES, opsLimit: 16, memLimitBytes: 268435456 }).success,
    ).toBe(true);
    expect(
      recoveryStartResponse.safeParse({ userId: ID, masterKeyByRecovery: BYTES }).success,
    ).toBe(true);
    expect(
      recoveryCompleteRequest.safeParse({
        username: 'alice',
        recoveryLoginKey: BYTES,
        deviceLabel: 'Android',
        salt: BYTES,
        opsLimit: 32,
        memLimitBytes: 134217728,
        loginKey: BYTES,
        masterKeyByKek: BYTES,
      }).success,
    ).toBe(true);
    expect(
      passwordChangeRequest.safeParse({
        currentLoginKey: BYTES,
        salt: BYTES,
        opsLimit: 32,
        memLimitBytes: 134217728,
        loginKey: BYTES,
        masterKeyByKek: BYTES,
      }).success,
    ).toBe(true);
  });

  it('parses the dto shapes', () => {
    expect(sessionDto.safeParse(session()).success).toBe(true);
    expect(sessionDto.safeParse({ ...session(), token: 'kdk_s_short' }).success).toBe(false);
    expect(userDto.safeParse(user()).success).toBe(true);
    expect(userDto.safeParse(user({ quotaBytes: 1000, status: 'pending_deletion' })).success).toBe(
      true,
    );
    expect(userDto.safeParse(user({ role: 'owner' })).success).toBe(false);
    expect(keyBundleDto.safeParse(keyBundle()).success).toBe(true);
    expect(signUpResponse.safeParse({ session: session(), user: user() }).success).toBe(true);
    expect(
      signInResponse.safeParse({ session: session(), user: user(), keyBundle: keyBundle() })
        .success,
    ).toBe(true);
    expect(sessionResponse.safeParse({ user: user() }).success).toBe(true);
    expect(passwordChangeResponse.safeParse({ session: session() }).success).toBe(true);
    expect(deletionRequestResponse.safeParse({ deletionDueAt: MOMENT }).success).toBe(true);
  });
});

describe('auth routes', () => {
  it('registers every auth and users pattern', () => {
    expect(ROUTE_PATTERNS.authParams).toBe('/api/v1/auth/params');
    expect(ROUTE_PATTERNS.authSignUp).toBe('/api/v1/auth/sign-up');
    expect(ROUTE_PATTERNS.authSignIn).toBe('/api/v1/auth/sign-in');
    expect(ROUTE_PATTERNS.authKeyBundle).toBe('/api/v1/auth/key-bundle');
    expect(ROUTE_PATTERNS.authRecoveryStart).toBe('/api/v1/auth/recovery/start');
    expect(ROUTE_PATTERNS.authRecoveryComplete).toBe('/api/v1/auth/recovery/complete');
    expect(ROUTE_PATTERNS.authPassword).toBe('/api/v1/auth/password');
    expect(ROUTE_PATTERNS.authSignOut).toBe('/api/v1/auth/sign-out');
    expect(ROUTE_PATTERNS.authSession).toBe('/api/v1/auth/session');
    expect(ROUTE_PATTERNS.usersMe).toBe('/api/v1/users/me');
    expect(ROUTE_PATTERNS.usersMeDelete).toBe('/api/v1/users/me/delete');
    expect(ROUTE_PATTERNS.usersMeDeleteCancel).toBe('/api/v1/users/me/delete/cancel');
  });
});
