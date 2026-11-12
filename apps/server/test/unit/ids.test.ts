import { describe, expect, it } from 'vitest';
import { ID_REGEX } from '@kodakiko/contract';
import { randomId, randomToken } from '../../src/platform/ids.ts';

describe('ids', () => {
  it('randomId matches the id pattern and differs between calls', () => {
    const first = randomId();
    expect(first).toMatch(ID_REGEX);
    expect(randomId()).not.toBe(first);
  });

  it('randomToken length follows the byte count', () => {
    expect(randomToken(16)).toHaveLength(22);
    expect(randomToken(32)).toHaveLength(43);
  });
});
