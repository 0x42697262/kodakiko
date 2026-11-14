import { describe, expect, it } from 'vitest';
import { CROP_MIN_FRACTION, FULL_CROP, dragCorner, moveCrop, toPixels } from '../src/features/entries/crop-math';

describe('crop math (spec 25, D-90)', () => {
  it('converts a normalized rectangle to whole pixels inside the image', () => {
    expect(toPixels({ x: 0.1, y: 0.2, width: 0.5, height: 0.5 }, 1000, 800)).toEqual({ originX: 100, originY: 160, width: 500, height: 400 });
    expect(toPixels(FULL_CROP, 333, 221)).toEqual({ originX: 0, originY: 0, width: 333, height: 221 });
  });

  it('drags a corner but keeps a minimum size and stays inside the image', () => {
    const shrunk = dragCorner(FULL_CROP, 'topLeft', 0.99, 0.99);
    expect(shrunk.width).toBeCloseTo(CROP_MIN_FRACTION);
    expect(shrunk.x + shrunk.width).toBeCloseTo(1);
    const grown = dragCorner({ x: 0.2, y: 0.2, width: 0.5, height: 0.5 }, 'bottomRight', 0.9, 0.9);
    expect(grown.width).toBeCloseTo(0.7);
    expect(grown.height).toBeCloseTo(0.7);
    const outside = dragCorner({ x: 0.2, y: 0.2, width: 0.5, height: 0.5 }, 'topLeft', -0.5, -0.5);
    expect(outside.x).toBe(0);
    expect(outside.y).toBe(0);
  });

  it('moves the whole rectangle without leaving the image', () => {
    expect(moveCrop({ x: 0.5, y: 0.5, width: 0.4, height: 0.4 }, 0.3, -0.7)).toEqual({ x: 0.6, y: 0, width: 0.4, height: 0.4 });
  });
});
