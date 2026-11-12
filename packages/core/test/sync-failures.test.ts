import { describe, expect, it } from 'vitest';
import {
  BACKOFF_BASE_MS,
  BACKOFF_CAP_MS,
  MAX_ATTEMPTS,
  classifyPushFailure,
  nextAttemptAt,
  type PushFailure,
} from '../src/index.ts';
import type { ErrorCode, OutboxKind } from '@kodakiko/contract';

const NOW = 5_000_000;
const HALF = () => 0.5;

function http(status: number, code: ErrorCode, retryAfterSeconds: number | null = null): PushFailure {
  return { kind: 'http', status, code, retryAfterSeconds };
}

describe('nextAttemptAt', () => {
  it('multiplies the random fraction by the exponential ceiling', () => {
    expect(nextAttemptAt(0, NOW, HALF)).toBe(NOW + BACKOFF_BASE_MS * 0.5);
    expect(nextAttemptAt(3, NOW, HALF)).toBe(NOW + BACKOFF_BASE_MS * 8 * 0.5);
    expect(nextAttemptAt(2, NOW, () => 0)).toBe(NOW);
  });

  it('caps the ceiling', () => {
    expect(nextAttemptAt(20, NOW, () => 1)).toBe(NOW + BACKOFF_CAP_MS);
  });
});

describe('classifyPushFailure', () => {
  const kinds: readonly OutboxKind[] = [
    'createEntry',
    'updateEntryMeta',
    'addMedia',
    'removeMedia',
    'trashEntry',
    'restoreEntry',
    'deleteEntry',
  ];

  it('retries network failures with backoff', () => {
    expect(classifyPushFailure('createEntry', { kind: 'network' }, 2, NOW, HALF)).toEqual({
      action: 'retry',
      at: nextAttemptAt(2, NOW, HALF),
    });
  });

  it('retries 5xx and STORAGE_UNAVAILABLE with backoff', () => {
    expect(classifyPushFailure('addMedia', http(503, 'INTERNAL'), 1, NOW, HALF)).toEqual({
      action: 'retry',
      at: nextAttemptAt(1, NOW, HALF),
    });
    expect(classifyPushFailure('addMedia', http(502, 'STORAGE_UNAVAILABLE'), 1, NOW, HALF)).toEqual({
      action: 'retry',
      at: nextAttemptAt(1, NOW, HALF),
    });
  });

  it('goes dead once attempts reach MAX_ATTEMPTS', () => {
    expect(classifyPushFailure('createEntry', { kind: 'network' }, MAX_ATTEMPTS, NOW, HALF)).toEqual({ action: 'dead' });
    expect(classifyPushFailure('createEntry', http(500, 'INTERNAL'), MAX_ATTEMPTS, NOW, HALF)).toEqual({ action: 'dead' });
    expect(classifyPushFailure('createEntry', { kind: 'network' }, MAX_ATTEMPTS - 1, NOW, HALF).action).toBe('retry');
  });

  it('waits for Retry-After on 429', () => {
    expect(classifyPushFailure('createEntry', http(429, 'RATE_LIMITED', 30), 1, NOW, HALF)).toEqual({
      action: 'retry',
      at: NOW + 30_000,
    });
  });

  it('falls back to backoff on 429 without Retry-After', () => {
    expect(classifyPushFailure('createEntry', http(429, 'RATE_LIMITED'), 1, NOW, HALF)).toEqual({
      action: 'retry',
      at: nextAttemptAt(1, NOW, HALF),
    });
  });

  it('never dead-letters a 429, even beyond MAX_ATTEMPTS', () => {
    expect(
      classifyPushFailure('createEntry', http(429, 'RATE_LIMITED', 5), MAX_ATTEMPTS + 3, NOW, HALF),
    ).toEqual({ action: 'retry', at: NOW + 5000 });
    expect(
      classifyPushFailure('createEntry', http(429, 'RATE_LIMITED'), MAX_ATTEMPTS + 3, NOW, HALF).action,
    ).toBe('retry');
  });

  it('maps REVISION_CONFLICT to conflict', () => {
    expect(classifyPushFailure('updateEntryMeta', http(409, 'REVISION_CONFLICT'), 0, NOW, HALF)).toEqual({ action: 'conflict' });
  });

  it('drops 404 for update-like kinds', () => {
    for (const kind of ['updateEntryMeta', 'trashEntry', 'restoreEntry', 'removeMedia', 'deleteEntry'] as const) {
      expect(classifyPushFailure(kind, http(404, 'NOT_FOUND'), 0, NOW, HALF)).toEqual({ action: 'drop' });
    }
  });

  it('dead-letters 404 for createEntry and addMedia', () => {
    for (const kind of ['createEntry', 'addMedia'] as const) {
      expect(classifyPushFailure(kind, http(404, 'NOT_FOUND'), 0, NOW, HALF)).toEqual({ action: 'dead' });
    }
  });

  it('signs out on 401', () => {
    for (const kind of kinds) {
      expect(classifyPushFailure(kind, http(401, 'UNAUTHENTICATED'), 0, NOW, HALF)).toEqual({ action: 'signedOut' });
    }
  });

  it('dead-letters quota, disabled feature, 413, 422 and other 4xx immediately', () => {
    const failures = [
      http(403, 'QUOTA_EXCEEDED'),
      http(403, 'FEATURE_DISABLED'),
      http(413, 'PAYLOAD_TOO_LARGE'),
      http(422, 'VALIDATION_FAILED'),
      http(409, 'ID_CONFLICT'),
      http(400, 'MALFORMED_REQUEST'),
    ];
    for (const failure of failures) {
      expect(classifyPushFailure('createEntry', failure, 0, NOW, HALF)).toEqual({ action: 'dead' });
    }
  });
});
