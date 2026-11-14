import { describe, expect, it } from 'vitest';
import { shutterAction } from '../src/features/capture/shutter-action';

describe('shutterAction (spec 22 §11.3)', () => {
  it('takes a photo on tap in photo mode and toggles recording on tap in video mode', () => {
    expect(shutterAction('photo', 'tap', false, false)).toBe('photo');
    expect(shutterAction('video', 'tap', false, false)).toBe('start');
    expect(shutterAction('video', 'tap', false, true)).toBe('stop');
  });

  it('records while holding only in photo mode with hold-to-record on', () => {
    expect(shutterAction('photo', 'holdStart', true, false)).toBe('startCapped');
    expect(shutterAction('photo', 'holdEnd', true, true)).toBe('stop');
    expect(shutterAction('photo', 'holdStart', false, false)).toBe('photo');
    expect(shutterAction('photo', 'holdEnd', false, false)).toBe('none');
    expect(shutterAction('video', 'holdStart', true, false)).toBe('start');
    expect(shutterAction('video', 'holdEnd', true, true)).toBe('none');
  });
});
