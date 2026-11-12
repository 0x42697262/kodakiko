import { backoffDelayMs, type RandomSource } from '../../platform/backoff.ts';

export type RetryOptions = {
  readonly attempts: number;
  readonly baseDelayMs: number;
  readonly capDelayMs: number;
  readonly isRetryable: (error: unknown) => boolean;
  readonly random: RandomSource;
  readonly sleep: (milliseconds: number) => Promise<void>;
};

export async function withRetry<Result>(
  operation: () => Promise<Result>,
  options: RetryOptions,
): Promise<Result> {
  let attempt = 1;
  for (;;) {
    try {
      return await operation();
    } catch (error) {
      if (attempt >= options.attempts || !options.isRetryable(error)) throw error;
      await options.sleep(
        backoffDelayMs(attempt, options.baseDelayMs, options.capDelayMs, options.random),
      );
      attempt += 1;
    }
  }
}
