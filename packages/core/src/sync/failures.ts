import type { ErrorCode, OutboxKind } from '@kodakiko/contract';
import {
  BACKOFF_BASE_MS,
  BACKOFF_CAP_MS,
  BACKOFF_FACTOR,
  MAX_ATTEMPTS,
  MS_PER_SECOND,
  NOT_FOUND_STATUS,
  SERVER_ERROR_STATUS_MIN,
  TOO_MANY_REQUESTS_STATUS,
  UNAUTHORIZED_STATUS,
} from './constants.ts';

export type PushFailure =
  | { readonly kind: 'network' }
  | {
      readonly kind: 'http';
      readonly status: number;
      readonly code: ErrorCode;
      readonly retryAfterSeconds: number | null;
    };

export type FailureDecision =
  | { readonly action: 'retry'; readonly at: number }
  | { readonly action: 'conflict' }
  | { readonly action: 'drop' }
  | { readonly action: 'dead' }
  | { readonly action: 'signedOut' };

const DROP_ON_NOT_FOUND: ReadonlySet<OutboxKind> = new Set([
  'updateEntryMeta',
  'trashEntry',
  'restoreEntry',
  'removeMedia',
  'deleteEntry',
]);

export function nextAttemptAt(attempts: number, now: number, random: () => number): number {
  return now + random() * Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * BACKOFF_FACTOR ** attempts);
}

function retryOrDead(attempts: number, at: number): FailureDecision {
  return attempts >= MAX_ATTEMPTS ? { action: 'dead' } : { action: 'retry', at };
}

function classifyHttp(
  kind: OutboxKind,
  failure: Extract<PushFailure, { kind: 'http' }>,
  attempts: number,
  now: number,
  random: () => number,
): FailureDecision {
  if (failure.status === UNAUTHORIZED_STATUS) return { action: 'signedOut' };
  if (failure.code === 'REVISION_CONFLICT') return { action: 'conflict' };
  if (failure.status === NOT_FOUND_STATUS) {
    return DROP_ON_NOT_FOUND.has(kind) ? { action: 'drop' } : { action: 'dead' };
  }
  if (failure.status === TOO_MANY_REQUESTS_STATUS) {
    const at =
      failure.retryAfterSeconds === null
        ? nextAttemptAt(attempts, now, random)
        : now + failure.retryAfterSeconds * MS_PER_SECOND;
    return { action: 'retry', at };
  }
  if (failure.status >= SERVER_ERROR_STATUS_MIN || failure.code === 'STORAGE_UNAVAILABLE') {
    return retryOrDead(attempts, nextAttemptAt(attempts, now, random));
  }
  return { action: 'dead' };
}

export function classifyPushFailure(
  kind: OutboxKind,
  failure: PushFailure,
  attempts: number,
  now: number,
  random: () => number,
): FailureDecision {
  if (failure.kind === 'network') return retryOrDead(attempts, nextAttemptAt(attempts, now, random));
  return classifyHttp(kind, failure, attempts, now, random);
}
