import { describe, expect, it } from 'vitest';
import { createFailedSignInTracker } from '../../src/adapters/memory/failed-sign-in-tracker.ts';

const WINDOW_MS = 60_000;
const START = new Date('2026-11-12T12:00:00.000Z');

function at(offsetMs: number): Date {
  return new Date(START.getTime() + offsetMs);
}

describe('failed sign-in tracker', () => {
  it('counts failures inside the window and clears them', () => {
    const tracker = createFailedSignInTracker({ windowMs: WINDOW_MS, maxEntries: 10 });
    expect(tracker.failureCount('a', START)).toBe(0);
    tracker.recordFailure('a', START);
    tracker.recordFailure('a', at(1000));
    expect(tracker.failureCount('a', at(2000))).toBe(2);
    expect(tracker.failureCount('b', at(2000))).toBe(0);
    tracker.clear('a');
    expect(tracker.failureCount('a', at(2000))).toBe(0);
  });

  it('forgets failures after the window', () => {
    const tracker = createFailedSignInTracker({ windowMs: WINDOW_MS, maxEntries: 10 });
    tracker.recordFailure('a', START);
    expect(tracker.failureCount('a', at(WINDOW_MS - 1))).toBe(1);
    expect(tracker.failureCount('a', at(WINDOW_MS))).toBe(0);
    tracker.recordFailure('a', at(WINDOW_MS));
    expect(tracker.failureCount('a', at(WINDOW_MS))).toBe(1);
  });

  it('evicts the oldest entry beyond the maximum', () => {
    const tracker = createFailedSignInTracker({ windowMs: WINDOW_MS, maxEntries: 2 });
    tracker.recordFailure('a', START);
    tracker.recordFailure('b', START);
    tracker.recordFailure('c', START);
    expect(tracker.failureCount('a', START)).toBe(0);
    expect(tracker.failureCount('b', START)).toBe(1);
    expect(tracker.failureCount('c', START)).toBe(1);
  });
});
