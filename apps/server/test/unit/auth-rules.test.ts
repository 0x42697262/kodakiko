import { describe, expect, it } from 'vitest';
import {
  failedSignInDelayMs,
  kdfParamsAcceptable,
  kdfWorkWithinBound,
  routeAllowedWhilePendingDeletion,
  sessionExpiry,
  shouldTouchSession,
  signUpAllowed,
  type InviteState,
} from '../../src/domain/auth-rules.ts';
import { ROUTE_PATTERNS } from '@kodakiko/contract';

const NOW = new Date('2026-11-12T12:00:00.000Z');
const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;
const FLOOR_MEM = 134_217_728;
const SALT = 16;

function invite(overrides: Partial<InviteState> = {}): InviteState {
  return {
    role: 'admin',
    expiresAt: new Date(NOW.getTime() + DAY_MS),
    redeemedAt: null,
    revokedAt: null,
    ...overrides,
  };
}

describe('kdfParamsAcceptable', () => {
  it.each([
    ['floor exactly', { saltLength: SALT, opsLimit: 32, memLimitBytes: FLOOR_MEM }, true],
    ['start params', { saltLength: SALT, opsLimit: 16, memLimitBytes: 268_435_456 }, true],
    ['too little work', { saltLength: SALT, opsLimit: 31, memLimitBytes: FLOOR_MEM }, false],
    ['too little memory', { saltLength: SALT, opsLimit: 64, memLimitBytes: 67_108_864 }, false],
    ['short salt', { saltLength: SALT - 1, opsLimit: 32, memLimitBytes: FLOOR_MEM }, false],
    ['long salt', { saltLength: SALT + 1, opsLimit: 32, memLimitBytes: FLOOR_MEM }, false],
    ['fast test params', { saltLength: SALT, opsLimit: 2, memLimitBytes: 8_388_608 }, false],
  ])('%s', (_name, params, expected) => {
    expect(kdfParamsAcceptable(params)).toBe(expected);
  });
});

describe('kdfWorkWithinBound', () => {
  it.each([
    ['floor', 32, FLOOR_MEM, true],
    ['exactly the bound', 2 ** 31, 2 ** 31, true],
    ['just above the bound', 2 ** 31 + 1, 2 ** 31, false],
    ['int4 ops with 2 ** 33 memory', 2_147_483_647, 2 ** 33, false],
    ['int4 ops with 2 ** 31 memory', 2_147_483_647, 2 ** 31, true],
  ])('%s', (_name, opsLimit, memLimitBytes, expected) => {
    expect(kdfWorkWithinBound({ opsLimit, memLimitBytes })).toBe(expected);
  });
});

describe('signUpAllowed', () => {
  it.each([
    ['invite mode, valid invite', 'invite', invite(), { allowed: true, role: 'admin' }],
    [
      'invite mode, member invite',
      'invite',
      invite({ role: 'member' }),
      { allowed: true, role: 'member' },
    ],
    ['invite mode, no invite', 'invite', null, { allowed: false }],
    [
      'invite mode, redeemed',
      'invite',
      invite({ redeemedAt: new Date(NOW.getTime() - MINUTE_MS) }),
      { allowed: false },
    ],
    [
      'invite mode, revoked',
      'invite',
      invite({ revokedAt: new Date(NOW.getTime() - MINUTE_MS) }),
      { allowed: false },
    ],
    ['invite mode, expired', 'invite', invite({ expiresAt: NOW }), { allowed: false }],
    [
      'invite mode, expired earlier',
      'invite',
      invite({ expiresAt: new Date(NOW.getTime() - 1) }),
      { allowed: false },
    ],
    ['open mode, no invite', 'open', null, { allowed: true, role: 'member' }],
    ['open mode ignores an admin invite', 'open', invite(), { allowed: true, role: 'member' }],
    [
      'open mode ignores a dead invite',
      'open',
      invite({ redeemedAt: NOW }),
      { allowed: true, role: 'member' },
    ],
  ] as const)('%s', (_name, mode, state, expected) => {
    expect(signUpAllowed(mode, state, NOW)).toEqual(expected);
  });
});

describe('sessionExpiry and shouldTouchSession', () => {
  it('adds the idle days', () => {
    expect(sessionExpiry(NOW, 30).toISOString()).toBe('2026-12-12T12:00:00.000Z');
    expect(sessionExpiry(NOW, 1).getTime() - NOW.getTime()).toBe(DAY_MS);
  });

  it.each([
    [4 * MINUTE_MS, false],
    [5 * MINUTE_MS - 1, false],
    [5 * MINUTE_MS, true],
    [6 * MINUTE_MS, true],
    [0, false],
  ])('last used %d ms ago → %s', (ago, expected) => {
    expect(shouldTouchSession(new Date(NOW.getTime() - ago), NOW)).toBe(expected);
  });
});

describe('failedSignInDelayMs', () => {
  it.each([
    [0, 10, 0],
    [9, 10, 0],
    [10, 10, 1000],
    [11, 10, 2000],
    [12, 10, 4000],
    [14, 10, 16_000],
    [15, 10, 30_000],
    [100, 10, 30_000],
    [1, 1, 1000],
    [0, 1, 0],
  ])('%d failures, max %d → %d ms', (failures, max, expected) => {
    expect(failedSignInDelayMs(failures, max)).toBe(expected);
  });
});

describe('routeAllowedWhilePendingDeletion', () => {
  const allowed = ['authSession', 'authSignOut', 'usersMe', 'usersMeDeleteCancel'];

  it('allows exactly the four documented routes', () => {
    for (const routeId of Object.keys(ROUTE_PATTERNS)) {
      expect(routeAllowedWhilePendingDeletion(routeId)).toBe(allowed.includes(routeId));
    }
  });

  it('refuses unknown ids', () => {
    expect(routeAllowedWhilePendingDeletion('somethingElse')).toBe(false);
    expect(routeAllowedWhilePendingDeletion('')).toBe(false);
  });
});
