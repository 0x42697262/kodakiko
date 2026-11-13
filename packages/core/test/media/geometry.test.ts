import { describe, expect, it } from 'vitest';
import { displaySize, fitWithin } from '../../src/index.ts';

const MAX_EDGE = 512;

describe('fitWithin', () => {
  it.each([
    [4000, 3000, { width: 512, height: 384 }],
    [3000, 4000, { width: 384, height: 512 }],
    [4032, 3024, { width: 512, height: 384 }],
    [1000, 333, { width: 512, height: 170 }],
    [512, 512, { width: 512, height: 512 }],
    [300, 200, { width: 300, height: 200 }],
    [1, 1, { width: 1, height: 1 }],
    [1, 10_000, { width: 1, height: 512 }],
    [10_000, 1, { width: 512, height: 1 }],
  ])('fits %i x %i within the maximum edge', (width, height, expected) => {
    expect(fitWithin(width, height, MAX_EDGE)).toEqual(expected);
  });

  it('never upscales', () => {
    expect(fitWithin(100, 50, 1024)).toEqual({ width: 100, height: 50 });
  });
});

describe('displaySize', () => {
  it.each([1, 2, 3, 4])('keeps the size for orientation %i', (orientation) => {
    expect(displaySize(4000, 3000, orientation)).toEqual({ width: 4000, height: 3000 });
  });

  it.each([5, 6, 7, 8])('swaps width and height for orientation %i', (orientation) => {
    expect(displaySize(4000, 3000, orientation)).toEqual({ width: 3000, height: 4000 });
  });

  it('keeps the size when there is no orientation', () => {
    expect(displaySize(4000, 3000, null)).toEqual({ width: 4000, height: 3000 });
  });
});
