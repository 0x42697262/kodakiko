import { SALT_BYTES, meetsKdfFloor } from '@kodakiko/core';
import { KDF_WORK_MAX, type SIGNUP_MODES } from '@kodakiko/contract';
import {
  DAY_MS,
  FAILED_SIGN_IN_BASE_DELAY_MS,
  FAILED_SIGN_IN_DELAY_CAP_MS,
  MINUTE_MS,
  PENDING_DELETION_ALLOWED_ROUTE_IDS,
  SESSION_TOUCH_INTERVAL_MINUTES,
} from '../config/constants.ts';
import type { UserRole } from './enums.ts';

const BACKOFF_BASE = 2;

export type SignupMode = (typeof SIGNUP_MODES)[number];

export type InviteState = {
  readonly role: UserRole;
  readonly expiresAt: Date;
  readonly redeemedAt: Date | null;
  readonly revokedAt: Date | null;
};

export type SignUpDecision =
  | { readonly allowed: true; readonly role: UserRole }
  | { readonly allowed: false };

export function kdfParamsAcceptable(params: {
  readonly saltLength: number;
  readonly opsLimit: number;
  readonly memLimitBytes: number;
}): boolean {
  return params.saltLength === SALT_BYTES && meetsKdfFloor(params);
}

export function kdfWorkWithinBound(params: {
  readonly opsLimit: number;
  readonly memLimitBytes: number;
}): boolean {
  return params.memLimitBytes * params.opsLimit <= KDF_WORK_MAX;
}

function inviteUsable(invite: InviteState, now: Date): boolean {
  return (
    invite.redeemedAt === null &&
    invite.revokedAt === null &&
    invite.expiresAt.getTime() > now.getTime()
  );
}

export function signUpAllowed(
  mode: SignupMode,
  invite: InviteState | null,
  now: Date,
): SignUpDecision {
  if (mode === 'open') return { allowed: true, role: 'member' };
  if (invite === null || !inviteUsable(invite, now)) return { allowed: false };
  return { allowed: true, role: invite.role };
}

export function sessionExpiry(now: Date, idleDays: number): Date {
  return new Date(now.getTime() + idleDays * DAY_MS);
}

export function shouldTouchSession(lastUsedAt: Date, now: Date): boolean {
  return now.getTime() - lastUsedAt.getTime() >= SESSION_TOUCH_INTERVAL_MINUTES * MINUTE_MS;
}

export function failedSignInDelayMs(failuresInWindow: number, maxFailures: number): number {
  if (failuresInWindow < maxFailures) return 0;
  const delay = FAILED_SIGN_IN_BASE_DELAY_MS * BACKOFF_BASE ** (failuresInWindow - maxFailures);
  return Math.min(FAILED_SIGN_IN_DELAY_CAP_MS, delay);
}

export function routeAllowedWhilePendingDeletion(routeId: string): boolean {
  return PENDING_DELETION_ALLOWED_ROUTE_IDS.includes(routeId);
}
