import { describe, expect, it } from 'vitest';
import { shouldAutoplay } from '../src/features/video/autoplay';

const ON = { autoplayVideos: true, autoplayOnMobileData: false };

describe('shouldAutoplay (spec 25, D-91/D-99)', () => {
  it('plays only the current page and only when the setting is on', () => {
    expect(shouldAutoplay(ON, { active: true, cached: true, wifi: false })).toBe(true);
    expect(shouldAutoplay(ON, { active: false, cached: true, wifi: true })).toBe(false);
    expect(shouldAutoplay({ ...ON, autoplayVideos: false }, { active: true, cached: true, wifi: true })).toBe(false);
  });

  it('downloads a video that is not cached only on Wi-Fi unless mobile data is allowed', () => {
    expect(shouldAutoplay(ON, { active: true, cached: false, wifi: true })).toBe(true);
    expect(shouldAutoplay(ON, { active: true, cached: false, wifi: false })).toBe(false);
    expect(shouldAutoplay({ ...ON, autoplayOnMobileData: true }, { active: true, cached: false, wifi: false })).toBe(true);
  });
});
