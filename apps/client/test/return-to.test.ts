import { describe, expect, it } from 'vitest';
import { isProtectedRoute, nextRouteAfterUnlock } from '../src/services/lock/return-to';

describe('nextRouteAfterUnlock', () => {
  it('goes home without a return path', () => {
    expect(nextRouteAfterUnlock(null, false)).toBe('/');
  });
  it('returns to a remembered route', () => {
    expect(nextRouteAfterUnlock('/calendar', false)).toBe('/calendar');
    expect(nextRouteAfterUnlock('/day/2026-05-01', false)).toBe('/day/2026-05-01');
  });
  it('returns to an entry only when it still exists', () => {
    expect(nextRouteAfterUnlock('/entry/abc', true)).toBe('/entry/abc');
    expect(nextRouteAfterUnlock('/entry/abc', false)).toBe('/');
    expect(nextRouteAfterUnlock('/entry/abc/edit', false)).toBe('/');
  });
  it('never returns to an unprotected route', () => {
    expect(nextRouteAfterUnlock('/lock', true)).toBe('/');
    expect(nextRouteAfterUnlock('/sign-in', true)).toBe('/');
  });
});

describe('isProtectedRoute', () => {
  it.each(['/welcome', '/invite', '/sign-in', '/sign-up', '/securing', '/recover', '/lock', '/cancel-deletion', '/s/abc'])(
    '%s is unprotected',
    (path) => {
      expect(isProtectedRoute(path)).toBe(false);
    },
  );
  it.each(['/', '/calendar', '/recovery-key', '/trash', '/day/2026-05-01', '/play/a/b', '/entry/x', '/settings'])(
    '%s is protected',
    (path) => {
      expect(isProtectedRoute(path)).toBe(true);
    },
  );
});
