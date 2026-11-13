import { describe, expect, it } from 'vitest';
import { byteSourceFromBytes } from '../../src/index.ts';

const BYTES = Uint8Array.of(10, 20, 30, 40, 50);

describe('byteSourceFromBytes', () => {
  it('reports the size', () => {
    expect(byteSourceFromBytes(BYTES).size).toBe(5);
  });

  it('reads a range', async () => {
    expect([...(await byteSourceFromBytes(BYTES).read(1, 3))]).toEqual([20, 30, 40]);
  });

  it('returns the available bytes when the range crosses the end', async () => {
    expect([...(await byteSourceFromBytes(BYTES).read(3, 10))]).toEqual([40, 50]);
  });

  it('returns nothing at or after the end', async () => {
    expect((await byteSourceFromBytes(BYTES).read(5, 4)).length).toBe(0);
    expect((await byteSourceFromBytes(BYTES).read(99, 4)).length).toBe(0);
  });

  it('returns nothing for a zero length', async () => {
    expect((await byteSourceFromBytes(BYTES).read(2, 0)).length).toBe(0);
  });
});
