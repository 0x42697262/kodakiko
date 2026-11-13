import { describe, expect, it } from 'vitest';
import { formatDuration } from '../src/features/capture/format-duration';

describe('formatDuration', () => {
  it.each([
    [0, '00:00'],
    [999, '00:00'],
    [1000, '00:01'],
    [59_999, '00:59'],
    [60_000, '01:00'],
    [605_000, '10:05'],
    [-5, '00:00'],
  ])('formats %i ms as %s', (milliseconds, expected) => {
    expect(formatDuration(milliseconds)).toBe(expected);
  });
});
