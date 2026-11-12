import { describe, expect, it } from 'vitest';
import { backoffDelayMs } from '../../src/platform/backoff.ts';

const BASE_MS = 200;
const CAP_MS = 2000;
const ALWAYS_ZERO = () => 0;
const NEARLY_ONE = () => 0.999999;

describe('backoffDelayMs', () => {
  it('returns zero when the random source returns zero', () => {
    expect(backoffDelayMs(1, BASE_MS, CAP_MS, ALWAYS_ZERO)).toBe(0);
    expect(backoffDelayMs(5, BASE_MS, CAP_MS, ALWAYS_ZERO)).toBe(0);
  });

  it('grows the upper bound by doubling per retry', () => {
    expect(backoffDelayMs(1, BASE_MS, CAP_MS, NEARLY_ONE)).toBeLessThan(BASE_MS);
    expect(backoffDelayMs(1, BASE_MS, CAP_MS, NEARLY_ONE)).toBeGreaterThan(BASE_MS - 1);
    expect(backoffDelayMs(2, BASE_MS, CAP_MS, NEARLY_ONE)).toBeLessThan(BASE_MS * 2);
    expect(backoffDelayMs(3, BASE_MS, CAP_MS, NEARLY_ONE)).toBeLessThan(BASE_MS * 4);
  });

  it('never exceeds the cap', () => {
    for (const retry of [4, 5, 10, 50, 1000]) {
      expect(backoffDelayMs(retry, BASE_MS, CAP_MS, NEARLY_ONE)).toBeLessThanOrEqual(CAP_MS);
    }
  });

  it('scales linearly with the random value (full jitter)', () => {
    const half = backoffDelayMs(2, BASE_MS, CAP_MS, () => 0.5);
    expect(half).toBe(BASE_MS);
  });

  it('stays within bounds for many random values', () => {
    for (let step = 0; step < 100; step += 1) {
      const delay = backoffDelayMs(3, BASE_MS, CAP_MS, () => step / 100);
      expect(delay).toBeGreaterThanOrEqual(0);
      expect(delay).toBeLessThan(BASE_MS * 4);
    }
  });
});
