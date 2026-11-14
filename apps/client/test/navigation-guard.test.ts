import { describe, expect, it } from 'vitest';
import { createNavigationGuard, NAVIGATION_LOCK_MS } from '../src/navigation/navigation-guard';

describe('navigation guard (v0.2.1 fix B5)', () => {
  it('lets the first push through and drops repeats to the same page inside the lock window', () => {
    let now = 1_000;
    const guard = createNavigationGuard(() => now);
    expect(guard.allow('/tags')).toBe(true);
    for (let tap = 0; tap < 9; tap += 1) {
      now += 30;
      expect(guard.allow('/tags')).toBe(false);
    }
  });

  it('lets a different page through at once', () => {
    let now = 1_000;
    const guard = createNavigationGuard(() => now);
    guard.allow('/settings/storage');
    now += 30;
    expect(guard.allow('/settings/export')).toBe(true);
  });

  it('allows the same page again once the window has passed', () => {
    let now = 1_000;
    const guard = createNavigationGuard(() => now);
    guard.allow('/tags');
    now += NAVIGATION_LOCK_MS;
    expect(guard.allow('/tags')).toBe(true);
  });
});
