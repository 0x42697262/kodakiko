import { BACKOFF_GROWTH_FACTOR } from '../config/constants.ts';

export type RandomSource = () => number;

export function backoffDelayMs(
  retryNumber: number,
  baseDelayMs: number,
  capDelayMs: number,
  random: RandomSource,
): number {
  const ceiling = Math.min(capDelayMs, baseDelayMs * BACKOFF_GROWTH_FACTOR ** (retryNumber - 1));
  return random() * ceiling;
}
