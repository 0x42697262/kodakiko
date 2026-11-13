import { describe, expect, it } from 'vitest';
import { formatBytes } from '../src/features/format/format-bytes';

describe('formatBytes', () => {
  it.each([
    [0, '0 B'],
    [1023, '1023 B'],
    [1536, '1.5 KB'],
    [536_870_912, '512 MB'],
    [2_147_483_648, '2 GB'],
    [3_758_096_384, '3.5 GB'],
  ])('%d → %s', (bytes, text) => {
    expect(formatBytes(bytes)).toBe(text);
  });
});
