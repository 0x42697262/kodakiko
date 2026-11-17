import { describe, expect, it } from 'vitest';
import {
  SWIPE_DIRECTION_RATIO,
  SWIPE_MIN_DISTANCE_PX,
  ZOOM_MAX,
  ZOOM_MIN,
} from '../src/constants.ts';
import { canMove, keyCommand, stepIndex, swipeDirection } from '../src/viewer-model.ts';
import { clampScale, clampTranslate, maxTranslate, pinchScale, settleScale, zoomAround } from '../src/zoom-math.ts';

describe('stepIndex and canMove', () => {
  it('moves one item forward or back', () => {
    expect(stepIndex(0, 3, 'next')).toBe(1);
    expect(stepIndex(2, 3, 'previous')).toBe(1);
  });

  it('stays at the first and the last item instead of wrapping', () => {
    expect(stepIndex(0, 3, 'previous')).toBe(0);
    expect(stepIndex(2, 3, 'next')).toBe(2);
    expect(stepIndex(0, 1, 'next')).toBe(0);
    expect(canMove(0, 3, 'previous')).toBe(false);
    expect(canMove(0, 3, 'next')).toBe(true);
    expect(canMove(2, 3, 'next')).toBe(false);
    expect(canMove(2, 3, 'previous')).toBe(true);
    expect(canMove(0, 1, 'next')).toBe(false);
  });
});

describe('swipeDirection', () => {
  it('uses the app swipe thresholds', () => {
    expect(SWIPE_MIN_DISTANCE_PX).toBe(60);
    expect(SWIPE_DIRECTION_RATIO).toBe(1.5);
  });

  it('a swipe to the left shows the next item, to the right the previous one', () => {
    expect(swipeDirection({ dx: -SWIPE_MIN_DISTANCE_PX, dy: 0 })).toBe('next');
    expect(swipeDirection({ dx: SWIPE_MIN_DISTANCE_PX, dy: 0 })).toBe('previous');
    expect(swipeDirection({ dx: -200, dy: 40 })).toBe('next');
  });

  it('ignores short moves', () => {
    expect(swipeDirection({ dx: -(SWIPE_MIN_DISTANCE_PX - 1), dy: 0 })).toBeNull();
    expect(swipeDirection({ dx: 0, dy: 0 })).toBeNull();
  });

  it('ignores mostly vertical moves (scrolling, pull down)', () => {
    expect(swipeDirection({ dx: -90, dy: 61 })).toBeNull();
    expect(swipeDirection({ dx: -90, dy: 60 })).toBe('next');
    expect(swipeDirection({ dx: 10, dy: 300 })).toBeNull();
  });
});

describe('keyCommand', () => {
  it('maps the arrow keys and Escape', () => {
    expect(keyCommand('ArrowRight')).toBe('next');
    expect(keyCommand('ArrowLeft')).toBe('previous');
    expect(keyCommand('Escape')).toBe('close');
  });

  it('ignores other keys', () => {
    expect(keyCommand('Enter')).toBeNull();
    expect(keyCommand(' ')).toBeNull();
    expect(keyCommand('ArrowUp')).toBeNull();
  });
});

describe('zoom math (twin of the app zoom-math)', () => {
  it('uses the app zoom limits', () => {
    expect(ZOOM_MIN).toBe(1);
    expect(ZOOM_MAX).toBe(5);
  });

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

  it('allows no movement while the zoomed image still fits the viewport', () => {
    expect(maxTranslate(300, 400, 1)).toBe(0);
    expect(maxTranslate(200, 400, 1.5)).toBe(0);
  });

  it('allows half of the overflow on each side', () => {
    expect(maxTranslate(400, 400, 2)).toBe(200);
    expect(maxTranslate(400, 300, 2)).toBe(250);
    expect(maxTranslate(225, 400, 2)).toBe(25);
    expect(maxTranslate(225, 400, 1.5)).toBe(0);
  });

  it('clamps a translation to the allowed range on both sides', () => {
    expect(clampTranslate(500, 200)).toBe(200);
    expect(clampTranslate(-500, 200)).toBe(-200);
    expect(clampTranslate(120, 200)).toBe(120);
    expect(clampTranslate(30, 0)).toBe(0);
  });

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
    expect(zoomAround(0, 200, 200, 2)).toBe(0);
  });
});

describe('pinchScale', () => {
  it('scales by the ratio of the finger distances from the scale at the start of the pinch', () => {
    expect(pinchScale(1, 100, 200)).toBe(2);
    expect(pinchScale(2, 100, 150)).toBe(3);
    expect(pinchScale(3, 200, 100)).toBe(1.5);
  });

  it('stays within the zoom limits', () => {
    expect(pinchScale(1, 100, 50)).toBe(ZOOM_MIN);
    expect(pinchScale(4, 100, 1000)).toBe(ZOOM_MAX);
  });

  it('keeps the start scale when the start distance is zero', () => {
    expect(pinchScale(2, 0, 100)).toBe(2);
  });
});
