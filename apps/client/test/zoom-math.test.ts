import { describe, expect, it } from 'vitest';
import { clampScale, clampTranslate, maxTranslate, settleScale, zoomAround } from '../src/features/media/zoom-math';
import { ZOOM_MAX, ZOOM_MIN } from '../src/features/media/constants';

describe('clampScale and settleScale', () => {
  it('keeps the scale within the zoom limits', () => {
    expect(clampScale(0.5)).toBe(ZOOM_MIN);
    expect(clampScale(2)).toBe(2);
    expect(clampScale(9)).toBe(ZOOM_MAX);
  });

  it('snaps a scale just above 1 back to 1 when the gesture ends', () => {
    expect(settleScale(1.04)).toBe(1);
    expect(settleScale(1.05)).toBe(1.05);
    expect(settleScale(3)).toBe(3);
  });
});

describe('maxTranslate and clampTranslate', () => {
  it('allows no movement while the zoomed image still fits the viewport', () => {
    expect(maxTranslate(300, 400, 1)).toBe(0);
    expect(maxTranslate(200, 400, 1.5)).toBe(0);
  });

  it('allows half of the overflow on each side (landscape photo, horizontal axis)', () => {
    expect(maxTranslate(400, 400, 2)).toBe(200);
    expect(maxTranslate(400, 300, 2)).toBe(250);
  });

  it('handles a portrait photo narrower than the viewport', () => {
    expect(maxTranslate(225, 400, 2)).toBe(25);
    expect(maxTranslate(225, 400, 1.5)).toBe(0);
  });

  it('clamps a translation to the allowed range on both sides', () => {
    expect(clampTranslate(500, 200)).toBe(200);
    expect(clampTranslate(-500, 200)).toBe(-200);
    expect(clampTranslate(120, 200)).toBe(120);
    expect(clampTranslate(30, 0)).toBe(0);
  });
});

describe('zoomAround', () => {
  it('keeps the content point under the focal point fixed on screen', () => {
    const center = 200;
    const cases = [
      { translate: 0, scale: 1, nextScale: 2.5, focal: 320 },
      { translate: -40, scale: 2, nextScale: 4, focal: 90 },
      { translate: 60, scale: 3, nextScale: 1.5, focal: 250 },
    ];
    for (const { translate, scale, nextScale, focal } of cases) {
      const contentPoint = (focal - center - translate) / scale;
      const next = zoomAround(translate, focal, center, nextScale / scale);
      expect(center + next + nextScale * contentPoint).toBeCloseTo(focal, 9);
    }
  });

  it('does not move anything when zooming around the center from no translation', () => {
    expect(zoomAround(0, 200, 200, 2)).toBe(0);
  });
});
