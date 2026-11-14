import { describe, expect, it } from 'vitest';
import { shutterAction } from '../src/features/capture/shutter-action';

describe('shutterAction (spec 22 §11.3, behavior changed by the programmer for v0.2.1 B7)', () => {
  it('takes a photo on tap in photo mode and toggles an uncapped recording on tap in video mode', () => {
    expect(shutterAction('photo', 'tap', true, false, false)).toBe('photo');
    expect(shutterAction('video', 'tap', true, false, false)).toBe('start');
    expect(shutterAction('video', 'tap', true, true, false)).toBe('stop');
  });

  it('records while held in either mode when hold-to-record is on, capped, and stops on release', () => {
    expect(shutterAction('photo', 'holdStart', true, false, false)).toBe('startCapped');
    expect(shutterAction('video', 'holdStart', true, false, false)).toBe('startCapped');
    expect(shutterAction('photo', 'holdEnd', true, true, true)).toBe('stop');
    expect(shutterAction('video', 'holdEnd', true, true, true)).toBe('stop');
    expect(shutterAction('photo', 'holdEnd', true, false, true)).toBe('stop');
  });

  it('does not stop an uncapped recording when a later hold ends', () => {
    expect(shutterAction('video', 'holdStart', true, true, false)).toBe('none');
    expect(shutterAction('video', 'holdEnd', true, true, false)).toBe('none');
  });

  it('treats a hold like a tap when hold-to-record is off', () => {
    expect(shutterAction('photo', 'holdStart', false, false, false)).toBe('photo');
    expect(shutterAction('video', 'holdStart', false, false, false)).toBe('start');
    expect(shutterAction('photo', 'holdEnd', false, false, false)).toBe('none');
  });
});
