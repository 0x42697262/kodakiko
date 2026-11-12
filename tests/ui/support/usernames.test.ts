import { isValidUsername } from '@kodakiko/core';
import { describe, expect, it } from 'vitest';
import { uiUsername } from './usernames.ts';

describe('uiUsername', () => {
  it('builds ui-<flow without dash>-<project>-<n>', () => {
    expect(uiUsername('U-02', 'desktop', 0)).toBe('ui-u02-desktop-0');
    expect(uiUsername('U-02', 'mobile', 0)).toBe('ui-u02-mobile-0');
    expect(uiUsername('U-05', 'android', 0)).toBe('ui-u05-android-0');
    expect(uiUsername('U-17', 'desktop', 12)).toBe('ui-u17-desktop-12');
  });

  it('always passes the core username check', () => {
    for (const flow of ['U-00', 'U-09', 'U-21']) {
      for (const project of ['desktop', 'mobile', 'android'] as const) {
        for (const index of [0, 1, 9, 10, 99]) {
          const username = uiUsername(flow, project, index);
          expect(isValidUsername(username)).toBe(true);
          expect(username).toBe(username.toLowerCase());
        }
      }
    }
  });

  it('keeps the two web projects apart', () => {
    expect(uiUsername('U-02', 'desktop', 0)).not.toBe(uiUsername('U-02', 'mobile', 0));
  });
});
