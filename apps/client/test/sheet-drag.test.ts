import { describe, expect, it } from 'vitest';
import { SHEET_CLOSE_FRACTION, SHEET_FLING_VELOCITY, shouldCloseSheet } from '../src/features/gallery/sheet-drag';

describe('shouldCloseSheet (spec 24 phase 4)', () => {
  const height = 400;

  it('closes when dragged down past the close fraction of its height', () => {
    expect(shouldCloseSheet(height * SHEET_CLOSE_FRACTION + 1, 0, height)).toBe(true);
    expect(shouldCloseSheet(height * SHEET_CLOSE_FRACTION - 1, 0, height)).toBe(false);
  });

  it('closes on a downward fling even when the drag is short', () => {
    expect(shouldCloseSheet(20, SHEET_FLING_VELOCITY + 1, height)).toBe(true);
  });

  it('never closes when dragged up or flung up', () => {
    expect(shouldCloseSheet(-200, -SHEET_FLING_VELOCITY * 2, height)).toBe(false);
    expect(shouldCloseSheet(0, SHEET_FLING_VELOCITY * 2, height)).toBe(false);
  });
});
