import { describe, expect, it, vi } from 'vitest';

vi.mock('react-native', () => ({ Platform: { OS: 'android' } }));
vi.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));

const { systemInsets, FULL_SCREEN_MIN_BOTTOM, FULL_SCREEN_MIN_TOP } = await import('../src/ui/system-insets');

describe('systemInsets (v0.2.1 fix B8)', () => {
  const zero = { top: 0, bottom: 0, left: 0, right: 0 };

  it('keeps a gap for the camera cutout and the gesture bar in full screen on Android', () => {
    expect(systemInsets(zero, true, true)).toEqual({ ...zero, top: FULL_SCREEN_MIN_TOP, bottom: FULL_SCREEN_MIN_BOTTOM });
  });

  it('keeps larger real insets and changes nothing outside full screen or on web', () => {
    expect(systemInsets({ ...zero, top: 40, bottom: 30 }, true, true)).toEqual({ ...zero, top: 40, bottom: 30 });
    expect(systemInsets(zero, false, true)).toEqual(zero);
    expect(systemInsets(zero, true, false)).toEqual(zero);
  });
});
