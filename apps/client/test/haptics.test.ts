import { describe, expect, it, vi } from 'vitest';

vi.mock('expo-haptics', () => ({
  AndroidHaptics: { Long_Press: 'long-press', Toggle_On: 'toggle-on', Virtual_Key: 'virtual-key', Gesture_Start: 'gesture-start', Gesture_End: 'gesture-end', Confirm: 'confirm' },
  performAndroidHapticsAsync: vi.fn(),
}));
vi.mock('react-native', () => ({ Platform: { OS: 'android' } }));

const { createHaptics } = await import('../src/ui/haptics');

describe('haptics (spec 24 phase 5)', () => {
  it('maps each kind to an Android haptic type when on', () => {
    const perform = vi.fn(() => Promise.resolve());
    const haptics = createHaptics('android', perform);
    haptics.tick('select');
    haptics.tick('toggle');
    haptics.tick('shutter');
    haptics.tick('recordStart');
    haptics.tick('recordStop');
    haptics.tick('refresh');
    expect(perform.mock.calls.map((call) => (call as unknown[])[0])).toEqual([
      'long-press',
      'toggle-on',
      'virtual-key',
      'gesture-start',
      'gesture-end',
      'confirm',
    ]);
  });

  it('does nothing on web or when the setting is off', () => {
    const perform = vi.fn(() => Promise.resolve());
    createHaptics('web', perform).tick('select');
    const off = createHaptics('android', perform);
    off.setEnabled(false);
    off.tick('select');
    expect(perform).not.toHaveBeenCalled();
  });

  it('swallows failures from the device', () => {
    const haptics = createHaptics('android', () => Promise.reject(new Error('no vibrator')));
    expect(() => haptics.tick('select')).not.toThrow();
  });
});
