import { describe, expect, it } from 'vitest';
import { withRetry, type RetryOptions } from '../../src/adapters/storage/retry.ts';

class Transient extends Error {}
class Permanent extends Error {}

const ATTEMPTS = 3;
const BASE_MS = 200;
const CAP_MS = 2000;
const HALF = 0.5;

function harness(): { readonly delays: number[]; readonly options: RetryOptions } {
  const delays: number[] = [];
  return {
    delays,
    options: {
      attempts: ATTEMPTS,
      baseDelayMs: BASE_MS,
      capDelayMs: CAP_MS,
      isRetryable: (error) => error instanceof Transient,
      random: () => HALF,
      sleep: async (milliseconds) => {
        delays.push(milliseconds);
      },
    },
  };
}

describe('withRetry', () => {
  it('returns the first success without sleeping', async () => {
    const { delays, options } = harness();
    expect(await withRetry(async () => 'ok', options)).toBe('ok');
    expect(delays).toEqual([]);
  });

  it('retries a retryable error and succeeds on the last attempt', async () => {
    const { delays, options } = harness();
    let calls = 0;
    const result = await withRetry(async () => {
      calls += 1;
      if (calls < ATTEMPTS) throw new Transient('down');
      return calls;
    }, options);
    expect(result).toBe(ATTEMPTS);
    expect(delays).toEqual([BASE_MS * HALF, BASE_MS * 2 * HALF]);
  });

  it('throws the last error after the attempts are used', async () => {
    const { options } = harness();
    let calls = 0;
    await expect(
      withRetry(async () => {
        calls += 1;
        throw new Transient(`down ${calls}`);
      }, options),
    ).rejects.toThrow(`down ${ATTEMPTS}`);
    expect(calls).toBe(ATTEMPTS);
  });

  it('does not retry a non-retryable error', async () => {
    const { options } = harness();
    let calls = 0;
    await expect(
      withRetry(async () => {
        calls += 1;
        throw new Permanent('denied');
      }, options),
    ).rejects.toBeInstanceOf(Permanent);
    expect(calls).toBe(1);
  });
});
