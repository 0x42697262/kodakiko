import { describe, expect, it } from 'vitest';
import { systemClock } from '../src/adapters/clock';

describe('system clock', () => {
  it('returns epoch milliseconds and a matching ISO string', () => {
    const before = Date.now();
    const now = systemClock.now();
    expect(now).toBeGreaterThanOrEqual(before);
    expect(new Date(systemClock.nowIso()).getTime()).toBeGreaterThanOrEqual(now - 1000);
    expect(systemClock.nowIso()).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
  });
});
