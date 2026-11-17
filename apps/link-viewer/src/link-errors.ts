import { ONE_VIEW } from './constants.ts';
import { ApiError, NetworkError } from './http.ts';
import { TEXT } from './text.ts';

export const FAILURE_KINDS = Object.freeze([
  'incomplete',
  'invalid',
  'expired',
  'sessionEnded',
  'oneViewEnded',
  'offline',
] as const);

export type FailureKind = (typeof FAILURE_KINDS)[number];

const MESSAGES: Readonly<Record<FailureKind, string>> = Object.freeze({
  incomplete: TEXT.incomplete,
  invalid: TEXT.invalid,
  expired: TEXT.expired,
  sessionEnded: TEXT.sessionEnded,
  oneViewEnded: TEXT.oneViewEnded,
  offline: TEXT.offline,
});

export function failureKind(error: unknown, maxViews: number | null): FailureKind {
  if (error instanceof NetworkError) return 'offline';
  if (!(error instanceof ApiError)) return 'invalid';
  if (error.code === 'LINK_EXPIRED') return 'expired';
  if (error.code === 'LINK_VIEW_EXPIRED')
    return maxViews === ONE_VIEW ? 'oneViewEnded' : 'sessionEnded';
  return 'invalid';
}

export function failureMessage(kind: FailureKind): string {
  return MESSAGES[kind];
}
