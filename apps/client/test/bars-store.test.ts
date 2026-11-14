import { describe, expect, it } from 'vitest';
import { scrollDirectionHides, SCROLL_HIDE_THRESHOLD } from '../src/ui/bars-store';

describe('hide bars on scroll', () => {
  it('hides when scrolling down, shows when scrolling up or near the top, ignores small moves', () => {
    expect(scrollDirectionHides(100, 200)).toBe(true);
    expect(scrollDirectionHides(200, 100)).toBe(false);
    expect(scrollDirectionHides(300, 300 + SCROLL_HIDE_THRESHOLD / 2)).toBeNull();
    expect(scrollDirectionHides(200, SCROLL_HIDE_THRESHOLD)).toBe(false);
  });
});
