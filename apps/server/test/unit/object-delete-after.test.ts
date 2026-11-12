import { describe, expect, it } from 'vitest';
import { objectDeleteAfter } from '../../src/domain/objects.ts';

const NOW = new Date('2026-11-12T10:00:00.000Z');
const DAY_MS = 86_400_000;
const SECOND_MS = 1000;

describe('objectDeleteAfter', () => {
  it('uses the grace days when they are longer than the upload URL lifetime', () => {
    const storage = { objectGraceDays: 7, uploadUrlTtlSeconds: 86_400 };
    expect(objectDeleteAfter(NOW, storage).getTime()).toBe(NOW.getTime() + 7 * DAY_MS);
  });

  it('uses the upload URL lifetime when grace is zero', () => {
    const storage = { objectGraceDays: 0, uploadUrlTtlSeconds: 3600 };
    expect(objectDeleteAfter(NOW, storage).getTime()).toBe(NOW.getTime() + 3600 * SECOND_MS);
  });
});
